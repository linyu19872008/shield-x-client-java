package com.cdeer.shieldx.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cdeer.shieldx.model.Event;
import com.cdeer.shieldx.model.EventBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSpoolTest {

  @Test
  void saveReplayAndStats(@TempDir Path dir) throws Exception {
    FileSpool spool = new FileSpool(dir);
    spool.save(batch("evt-1", "evt-2"));

    SpoolStats stats = spool.stats();
    assertThat(stats.files()).isEqualTo(1);
    assertThat(stats.events()).isEqualTo(2);
    assertThat(stats.bytes()).isPositive();

    List<EventBatch> replayed = new ArrayList<>();
    spool.replay(replayed::add);

    assertThat(replayed).hasSize(1);
    assertThat(replayed.get(0).events()).hasSize(2);
    assertThat(spool.stats().files()).isZero();
  }

  @Test
  void corruptFileIsSkippedAndDeleted(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("v1-0000000000000000001-000001-1.json.gz"), "not gzip");
    FileSpool spool = new FileSpool(dir);

    spool.replay(batch -> {});

    assertThat(spool.stats().files()).isZero();
  }

  @Test
  void replayFailureKeepsFile(@TempDir Path dir) throws Exception {
    FileSpool spool = new FileSpool(dir);
    spool.save(batch("evt-1"));

    assertThatThrownBy(
            () ->
                spool.replay(
                    batch -> {
                      throw new IOException("boom");
                    }))
        .hasMessageContaining("boom");
    assertThat(spool.stats().files()).isEqualTo(1);
  }

  @Test
  void trimKeepsNewestFiles(@TempDir Path dir) throws Exception {
    FileSpool spool = new FileSpool(dir, 10 * 1024 * 1024, 2);
    spool.save(batch("evt-1"));
    spool.save(batch("evt-2"));
    spool.save(batch("evt-3"));

    assertThat(spool.stats().files()).isEqualTo(2);
  }

  @Test
  void emptyBatchIsNotPersisted(@TempDir Path dir) throws Exception {
    FileSpool spool = new FileSpool(dir);
    spool.save(new EventBatch(0, 0, null, List.of()));
    assertThat(spool.stats().files()).isZero();
  }

  private static EventBatch batch(String... ids) {
    List<Event> events = new ArrayList<>();
    for (String id : ids) {
      events.add(
          Event.builder()
              .eventId(id)
              .ts(Instant.parse("2026-11-14T16:00:00Z"))
              .clientIp("203.0.113.10")
              .host("example.com")
              .path("/")
              .method("GET")
              .status(200)
              .durationMs(1)
              .configRevision(39)
              .action("observe")
              .actionResult("passed")
              .decisionSource("none")
              .build());
    }
    return new EventBatch(39, 1, null, events);
  }
}
