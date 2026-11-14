package com.cdeer.shieldx.spool;

import com.cdeer.shieldx.client.ShieldXConfig;
import com.cdeer.shieldx.model.EventBatch;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Disk-backed batch spool: one gzip JSON file per batch, written with temp + atomic move. */
public final class FileSpool implements Spool {

  private static final int FORMAT_VERSION = 1;
  private static final String PREFIX = "v1-";
  private static final String SUFFIX = ".json.gz";
  private static final long DEFAULT_MAX_BYTES = 200L * 1024 * 1024;
  private static final int DEFAULT_MAX_FILES = 10_000;

  private final Path directory;
  private final long maxBytes;
  private final int maxFiles;
  private final ObjectMapper mapper;
  private final AtomicLong sequence = new AtomicLong();

  public FileSpool(Path directory) {
    this(directory, DEFAULT_MAX_BYTES, DEFAULT_MAX_FILES);
  }

  public FileSpool(Path directory, long maxBytes, int maxFiles) {
    if (directory == null) {
      throw new IllegalArgumentException("directory must not be null");
    }
    if (maxBytes <= 0 || maxFiles <= 0) {
      throw new IllegalArgumentException("maxBytes and maxFiles must be positive");
    }
    this.directory = directory;
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
    this.mapper = ShieldXConfig.defaultObjectMapper();
    createDirectory();
  }

  @Override
  public synchronized void save(EventBatch batch) throws IOException {
    if (batch == null || batch.events().isEmpty()) {
      return;
    }
    createDirectory();
    long now = System.currentTimeMillis();
    long seq = sequence.incrementAndGet();
    String name =
        String.format("%s%019d-%06d-%d%s", PREFIX, now, seq, batch.events().size(), SUFFIX);
    Path target = directory.resolve(name);
    Path temp = directory.resolve(name + ".tmp");

    try (OutputStream file = Files.newOutputStream(temp);
        GZIPOutputStream gzip = new GZIPOutputStream(file)) {
      mapper.writeValue(gzip, new SpoolRecord(FORMAT_VERSION, Instant.now(), batch));
    } catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    setFilePermissions(temp);
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    trim();
  }

  @Override
  public synchronized void replay(ReplayCallback callback) throws Exception {
    List<FileInfo> files = list();
    for (FileInfo file : files) {
      EventBatch batch;
      try {
        batch = read(file.path);
      } catch (IOException e) {
        // Corrupt files must not block replay forever.
        Files.deleteIfExists(file.path);
        continue;
      }
      callback.accept(batch);
      Files.deleteIfExists(file.path);
    }
  }

  @Override
  public synchronized SpoolStats stats() {
    try {
      List<FileInfo> files = list();
      long bytes = 0;
      int events = 0;
      for (FileInfo file : files) {
        bytes += file.size;
        events += file.events;
      }
      return new SpoolStats(files.size(), bytes, events);
    } catch (IOException e) {
      return SpoolStats.empty();
    }
  }

  @Override
  public synchronized void trim() throws IOException {
    List<FileInfo> files = list();
    long total = 0;
    for (FileInfo file : files) {
      total += file.size;
    }
    while (files.size() > 1 && (files.size() > maxFiles || total > maxBytes)) {
      FileInfo oldest = files.remove(0);
      Files.deleteIfExists(oldest.path);
      total -= oldest.size;
    }
  }

  private EventBatch read(Path path) throws IOException {
    try (InputStream file = Files.newInputStream(path);
        GZIPInputStream gzip = new GZIPInputStream(file)) {
      SpoolRecord record = mapper.readValue(gzip, SpoolRecord.class);
      if (record.version() != FORMAT_VERSION) {
        throw new IOException("unsupported spool version " + record.version());
      }
      return record.batch();
    }
  }

  private List<FileInfo> list() throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<FileInfo> files = new ArrayList<>();
    try (var stream = Files.list(directory)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                String name = path.getFileName().toString();
                return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
              })
          .forEach(
              path -> {
                try {
                  String name = path.getFileName().toString();
                  files.add(new FileInfo(path, Files.size(path), parseEvents(name), name));
                } catch (IOException ignored) {
                  // A file disappearing during listing is not fatal.
                }
              });
    }
    files.sort(Comparator.comparing(FileInfo::name));
    return files;
  }

  private static int parseEvents(String name) {
    String base = name.substring(0, name.length() - SUFFIX.length());
    String[] parts = base.split("-");
    if (parts.length < 4) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[parts.length - 1]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private void createDirectory() {
    try {
      Files.createDirectories(directory);
      try {
        Files.setPosixFilePermissions(
            directory,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX filesystems (for example Windows) keep platform defaults.
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to create spool directory " + directory, e);
    }
  }

  private static void setFilePermissions(Path path) {
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException ignored) {
      // Non-POSIX filesystems keep platform defaults.
    }
  }

  private record FileInfo(Path path, long size, int events, String name) {}

  private record SpoolRecord(
      @JsonProperty("v") int version,
      @JsonProperty("saved_at") Instant savedAt,
      @JsonProperty("batch") EventBatch batch) {}
}
