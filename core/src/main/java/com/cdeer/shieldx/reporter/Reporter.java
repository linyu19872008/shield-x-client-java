package com.cdeer.shieldx.reporter;

import com.cdeer.shieldx.client.Format;
import com.cdeer.shieldx.client.ReportOptions;
import com.cdeer.shieldx.client.ReportResult;
import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.error.BatchTooLargeException;
import com.cdeer.shieldx.error.ShieldXException;
import com.cdeer.shieldx.error.ValidationException;
import com.cdeer.shieldx.model.ClientStats;
import com.cdeer.shieldx.model.Event;
import com.cdeer.shieldx.model.EventBatch;
import com.cdeer.shieldx.spool.Spool;
import com.cdeer.shieldx.spool.SpoolStats;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Batches events, applies backpressure and handles 413/429/400/5xx failures.
 *
 * <p>Enqueue never blocks. When the queue is full the oldest event is dropped and counted.
 */
public final class Reporter implements AutoCloseable {

  private static final int DEFAULT_BATCH_SIZE = 300;
  private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(2);
  private static final int DEFAULT_QUEUE_SIZE = 10_000;
  private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(60);
  private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);

  private final ShieldXClient client;
  private final int batchSize;
  private final Duration flushInterval;
  private final int queueSize;
  private final ReportOptions reportOptions;
  private final Duration maxBackoff;
  private final Duration shutdownTimeout;
  private final Spool spool;
  private final Supplier<ReporterState> state;
  private final Consumer<Throwable> onError;
  private final BiConsumer<Integer, String> onDrop;

  private final EventQueue queue;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong enqueued = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong retried = new AtomicLong();
  private final AtomicLong spooled = new AtomicLong();
  private final AtomicLong replayed = new AtomicLong();

  private volatile Thread worker;
  private volatile Instant lastSuccess;
  private volatile Throwable lastError;
  private volatile Duration backoff = Duration.ZERO;

  private Reporter(Builder builder) {
    this.client = builder.client;
    this.batchSize = builder.batchSize;
    this.flushInterval = builder.flushInterval;
    this.queueSize = builder.queueSize;
    this.reportOptions =
        ReportOptions.builder()
            .format(builder.format)
            .gzip(builder.gzip)
            .skipValidation(builder.skipValidation)
            .build();
    this.maxBackoff = builder.maxBackoff;
    this.shutdownTimeout = builder.shutdownTimeout;
    this.spool = builder.spool;
    this.state = builder.state;
    this.onError = builder.onError;
    this.onDrop = builder.onDrop;
    this.queue = new EventQueue(queueSize);
  }

  public static Builder builder(ShieldXClient client) {
    return new Builder(client);
  }

  /** Starts the dispatcher. Calling start twice, or after close, is illegal. */
  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        throw new IllegalStateException("reporter is closed");
      }
      if (!running.compareAndSet(false, true)) {
        throw new IllegalStateException("reporter is already running");
      }
      Thread thread = new Thread(this::runLoop, "shieldx-reporter");
      thread.setDaemon(true);
      worker = thread;
      thread.start();
    }
  }

  /**
   * Adds an event without blocking. Returns false when the event was invalid or the reporter is
   * closed. A full queue drops the oldest event and still returns true.
   */
  public boolean enqueue(Event event) {
    Objects.requireNonNull(event, "event");
    if (closed.get()) {
      return false;
    }
    if (!reportOptions.skipValidation()) {
      try {
        event.validate();
      } catch (ValidationException e) {
        drop(1, "invalid_event");
        reportError(e);
        return false;
      }
    }
    int droppedNow = queue.offer(event);
    enqueued.incrementAndGet();
    if (droppedNow > 0) {
      drop(droppedNow, "queue_full");
    }
    return true;
  }

  /** Best-effort wakeup for the dispatcher. */
  public void flush() {
    queue.wake();
  }

  public ReporterStats stats() {
    SpoolStats spoolStats = spool == null ? SpoolStats.empty() : spool.stats();
    return new ReporterStats(
        enqueued.get(),
        dropped.get(),
        sent.get(),
        accepted.get(),
        rejected.get(),
        retried.get(),
        spooled.get(),
        replayed.get(),
        queue.size(),
        spoolStats.files(),
        spoolStats.bytes(),
        lastError,
        lastSuccess);
  }

  @Override
  public void close() {
    close(shutdownTimeout);
  }

  /** Stops accepting events and waits up to {@code timeout} for the final flush. */
  public void close(Duration timeout) {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      running.set(false);
      queue.close();
      Thread thread = worker;
      if (thread == null) {
        return;
      }
      try {
        thread.join(Math.max(1, timeout.toMillis()));
        if (thread.isAlive()) {
          thread.interrupt();
          thread.join(1000);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        thread.interrupt();
      }
    }
  }

  private void runLoop() {
    try {
      replaySpool();
      while (running.get()) {
        List<Event> events = queue.takeBatch(batchSize, flushInterval);
        if (events.isEmpty()) {
          continue;
        }
        sendAndHandle(events);
        Duration wait = backoff;
        if (!wait.isZero()) {
          sleep(wait);
        }
        if (running.get()) {
          replaySpool();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      drainOnShutdown();
    }
  }

  private void sendAndHandle(List<Event> events) {
    EventBatch batch = batchOf(events);
    SendOutcome outcome = sendBatch(batch);
    if (outcome.error() == null) {
      backoff = Duration.ZERO;
      return;
    }
    reportError(outcome.error());
    List<Event> remaining = outcome.remaining();
    if (remaining.isEmpty()) {
      return;
    }
    if (shouldDrop(outcome.error())) {
      drop(remaining.size(), "server_rejected");
      return;
    }
    if (spool != null) {
      EventBatch spoolBatch = batchOf(remaining);
      try {
        spool.save(spoolBatch);
        spooled.addAndGet(remaining.size());
        backoff = nextBackoff(outcome.error());
        return;
      } catch (IOException e) {
        reportError(e);
      }
    }
    int lost = queue.requeueFront(remaining);
    retried.incrementAndGet();
    if (lost > 0) {
      drop(lost, "queue_full");
    }
    backoff = nextBackoff(outcome.error());
  }

  private SendOutcome sendBatch(EventBatch batch) {
    try {
      send(batch);
      return new SendOutcome(List.of(), null);
    } catch (RuntimeException e) {
      if (isSplittable(e) && batch.events().size() > 1) {
        int mid = batch.events().size() / 2;
        EventBatch first = batchOf(batch.events().subList(0, mid));
        EventBatch second = batchOf(batch.events().subList(mid, batch.events().size()));
        SendOutcome firstOutcome = sendBatch(first);
        if (firstOutcome.error() != null) {
          List<Event> remaining = new ArrayList<>(firstOutcome.remaining());
          remaining.addAll(second.events());
          return new SendOutcome(remaining, firstOutcome.error());
        }
        return sendBatch(second);
      }
      return new SendOutcome(batch.events(), e);
    }
  }

  private void send(EventBatch batch) {
    ReportResult result = client.reportEvents(batch, reportOptions);
    sent.incrementAndGet();
    accepted.addAndGet(result.accepted());
    rejected.addAndGet(result.rejected());
    lastSuccess = Instant.now();
    lastError = null;
  }

  private boolean isSplittable(RuntimeException error) {
    if (error instanceof BatchTooLargeException) {
      return true;
    }
    return error instanceof ShieldXException shieldX && shieldX.statusCode() == 413;
  }

  private boolean shouldDrop(RuntimeException error) {
    if (error instanceof ValidationException || error instanceof BatchTooLargeException) {
      return true;
    }
    return error instanceof ShieldXException shieldX && shieldX.statusCode() == 400;
  }

  private EventBatch batchOf(List<Event> events) {
    ReporterState current;
    try {
      current = state.get();
    } catch (RuntimeException e) {
      current = new ReporterState(0, 0);
      reportError(e);
    }
    return new EventBatch(
        current.revision(), current.policyCount(), clientStats(), List.copyOf(events));
  }

  private ClientStats clientStats() {
    SpoolStats spoolStats = spool == null ? SpoolStats.empty() : spool.stats();
    return new ClientStats(queue.size(), dropped.get(), spoolStats.events());
  }

  private void replaySpool() {
    if (spool == null) {
      return;
    }
    try {
      spool.replay(
          batch -> {
            SendOutcome outcome = sendBatch(batch);
            if (outcome.error() != null) {
              throw new IOException("spool replay failed", outcome.error());
            }
            replayed.addAndGet(batch.events().size());
          });
    } catch (Exception e) {
      reportError(e);
    }
  }

  private void drainOnShutdown() {
    Instant deadline = Instant.now().plus(shutdownTimeout);
    while (true) {
      List<Event> events = queue.drainAll();
      if (events.isEmpty()) {
        return;
      }
      SendOutcome outcome = sendBatch(batchOf(events));
      if (outcome.error() != null) {
        reportError(outcome.error());
        if (spool != null) {
          try {
            spool.save(batchOf(outcome.remaining()));
            spooled.addAndGet(outcome.remaining().size());
          } catch (IOException e) {
            reportError(e);
            drop(outcome.remaining().size(), "shutdown");
          }
        } else {
          drop(outcome.remaining().size(), "shutdown");
        }
        return;
      }
      if (Instant.now().isAfter(deadline)) {
        return;
      }
    }
  }

  private Duration nextBackoff(Throwable error) {
    if (error instanceof ShieldXException shieldX
        && shieldX.retryAfter() != null
        && !shieldX.retryAfter().isZero()) {
      return shieldX.retryAfter().compareTo(maxBackoff) > 0 ? maxBackoff : shieldX.retryAfter();
    }
    Duration base;
    if (backoff.compareTo(BASE_BACKOFF) < 0) {
      base = BASE_BACKOFF;
    } else {
      base = backoff.multipliedBy(2);
      if (base.compareTo(maxBackoff) > 0) {
        base = maxBackoff;
      }
    }
    backoff = base;
    double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
    return Duration.ofMillis((long) (base.toMillis() * jitter));
  }

  private void sleep(Duration duration) throws InterruptedException {
    if (duration.isZero() || duration.isNegative()) {
      return;
    }
    Thread.sleep(duration.toMillis());
  }

  private void drop(int count, String reason) {
    if (count <= 0) {
      return;
    }
    dropped.addAndGet(count);
    if (onDrop != null) {
      try {
        onDrop.accept(count, reason);
      } catch (RuntimeException ignored) {
        // Callback failures must not break the dispatcher.
      }
    }
  }

  private void reportError(Throwable error) {
    if (error == null) {
      return;
    }
    lastError = error;
    if (onError != null) {
      try {
        onError.accept(error);
      } catch (RuntimeException ignored) {
        // Callback failures must not break the dispatcher.
      }
    }
  }

  private record SendOutcome(List<Event> remaining, RuntimeException error) {}

  /** Builder for {@link Reporter}. */
  public static final class Builder {
    private final ShieldXClient client;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;
    private int queueSize = DEFAULT_QUEUE_SIZE;
    private Format format = Format.NDJSON;
    private boolean gzip = true;
    private boolean skipValidation;
    private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
    private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
    private Spool spool;
    private Supplier<ReporterState> state = () -> new ReporterState(0, 0);
    private Consumer<Throwable> onError;
    private BiConsumer<Integer, String> onDrop;

    private Builder(ShieldXClient client) {
      this.client = Objects.requireNonNull(client, "client");
    }

    public Builder batchSize(int value) {
      if (value < 1 || value > 1000) {
        throw new IllegalArgumentException("batchSize must be 1..1000");
      }
      this.batchSize = value;
      return this;
    }

    public Builder flushInterval(Duration value) {
      Objects.requireNonNull(value, "flushInterval");
      if (value.isNegative()) {
        throw new IllegalArgumentException("flushInterval must not be negative");
      }
      this.flushInterval = value;
      return this;
    }

    public Builder queueSize(int value) {
      if (value < 1) {
        throw new IllegalArgumentException("queueSize must be positive");
      }
      this.queueSize = value;
      return this;
    }

    public Builder format(Format value) {
      this.format = Objects.requireNonNull(value, "format");
      return this;
    }

    public Builder gzip(boolean value) {
      this.gzip = value;
      return this;
    }

    public Builder skipValidation(boolean value) {
      this.skipValidation = value;
      return this;
    }

    public Builder maxBackoff(Duration value) {
      Objects.requireNonNull(value, "maxBackoff");
      if (value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException("maxBackoff must be positive");
      }
      this.maxBackoff = value;
      return this;
    }

    public Builder shutdownTimeout(Duration value) {
      Objects.requireNonNull(value, "shutdownTimeout");
      if (value.isNegative()) {
        throw new IllegalArgumentException("shutdownTimeout must not be negative");
      }
      this.shutdownTimeout = value;
      return this;
    }

    public Builder spool(Spool value) {
      this.spool = value;
      return this;
    }

    public Builder state(Supplier<ReporterState> value) {
      this.state = Objects.requireNonNull(value, "state");
      return this;
    }

    public Builder onError(Consumer<Throwable> value) {
      this.onError = value;
      return this;
    }

    public Builder onDrop(BiConsumer<Integer, String> value) {
      this.onDrop = value;
      return this;
    }

    public Reporter build() {
      return new Reporter(this);
    }
  }
}
