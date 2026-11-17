package com.cdeer.shieldx.poller;

import com.cdeer.shieldx.client.PoliciesResult;
import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.client.TemplatesResult;
import com.cdeer.shieldx.error.ShieldXException;
import com.cdeer.shieldx.model.Policy;
import com.cdeer.shieldx.model.PolicySnapshot;
import com.cdeer.shieldx.model.TemplateSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Periodically pulls policy snapshots and keeps the latest one.
 *
 * <p>Semantics: whole-snapshot replacement, empty list clears, failures keep the old snapshot, 304
 * does not re-apply.
 */
public final class Poller implements AutoCloseable {

  private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
  private static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  private final ShieldXClient client;
  private final Duration interval;
  private final boolean fetchTemplates;
  private final Consumer<PolicySnapshot> onSnapshot;
  private final LongConsumer onNotModified;
  private final Consumer<TemplateSnapshot> onTemplates;
  private final Consumer<Throwable> onError;

  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong polls = new AtomicLong();
  private final AtomicLong success = new AtomicLong();
  private final AtomicLong notModified = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();

  private volatile PolicySnapshot snapshot;
  private volatile long revision;
  private volatile long templateRevision;
  private volatile Instant lastSuccess;
  private volatile Throwable lastError;
  private volatile Duration currentBackoff = Duration.ZERO;

  private Poller(Builder builder) {
    this.client = builder.client;
    this.interval = builder.interval;
    this.revision = builder.initialRevision;
    this.fetchTemplates = builder.fetchTemplates;
    this.onSnapshot = builder.onSnapshot;
    this.onNotModified = builder.onNotModified;
    this.onTemplates = builder.onTemplates;
    this.onError = builder.onError;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "shieldx-poller");
              thread.setDaemon(true);
              return thread;
            });
  }

  public static Builder builder(ShieldXClient client) {
    return new Builder(client);
  }

  /** Starts polling immediately. Calling start twice, or after close, is illegal. */
  public synchronized void start() {
    if (closed.get()) {
      throw new IllegalStateException("poller is closed");
    }
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("poller is already running");
    }
    schedule(Duration.ZERO);
  }

  /** Pulls once now and returns the client error, if any. */
  public void refresh() {
    pollOnce();
  }

  /** Pulls templates once now and returns the client error, if any. */
  public void refreshTemplates() {
    fetchTemplates();
  }

  public PolicySnapshot snapshot() {
    return snapshot;
  }

  public long revision() {
    return revision;
  }

  public PollerStats stats() {
    return new PollerStats(
        polls.get(),
        success.get(),
        notModified.get(),
        failures.get(),
        revision,
        lastSuccess,
        lastError,
        currentBackoff);
  }

  private void schedule(Duration delay) {
    if (!running.get()) {
      return;
    }
    try {
      scheduler.schedule(this::runOnce, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // close() won the race; nothing to do.
    }
  }

  private void runOnce() {
    if (!running.get()) {
      return;
    }
    Duration next = interval;
    try {
      pollOnceInternal();
      currentBackoff = Duration.ZERO;
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      lastError = e;
      next = nextBackoff(e);
      currentBackoff = next;
      reportError(e);
    }
    schedule(next);
  }

  private void pollOnce() {
    try {
      pollOnceInternal();
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      lastError = e;
      currentBackoff = nextBackoff(e);
      reportError(e);
    }
  }

  private void pollOnceInternal() {
    polls.incrementAndGet();
    final long since = revision;
    PoliciesResult result = client.fetchPolicies(since);
    lastSuccess = Instant.now();
    lastError = null;
    if (result.notModified()) {
      revision = result.revision();
      notModified.incrementAndGet();
      if (onNotModified != null) {
        call(() -> onNotModified.accept(result.revision()));
      }
      return;
    }

    PoliciesResult effective = result;
    if (effective.snapshot() != null && effective.snapshot().isDelta()) {
      if (snapshot == null
          || effective.snapshot().resyncRequired()
          || effective.snapshot().baseRevision() != since) {
        revision = -1;
        throw new IllegalStateException(
            "received delta without a matching base or with resync required; forcing full resync");
      }
      effective =
          new PoliciesResult(
              effective.revision(), false, mergeDelta(snapshot, effective.snapshot()));
    }
    final PoliciesResult applied = effective;
    revision = applied.revision();
    snapshot = applied.snapshot();
    success.incrementAndGet();
    if (onSnapshot != null) {
      call(() -> onSnapshot.accept(applied.snapshot()));
    }
    if (fetchTemplates) {
      try {
        fetchTemplatesInternal();
      } catch (RuntimeException e) {
        reportError(e);
      }
    }
  }

  private static PolicySnapshot mergeDelta(PolicySnapshot base, PolicySnapshot delta) {
    Map<String, Policy> byId = new LinkedHashMap<>();
    for (Policy policy : base.policies()) {
      byId.put(policy.id(), policy);
    }
    for (Policy policy : delta.upserts()) {
      byId.put(policy.id(), policy);
    }
    for (String id : delta.deletes()) {
      byId.remove(id);
    }
    List<Policy> merged = new ArrayList<>(byId.values());
    merged.sort(Comparator.comparingInt(Policy::priority).reversed().thenComparing(Policy::id));
    return new PolicySnapshot(
        delta.revision(),
        delta.generatedAt(),
        merged.size(),
        delta.filteredOut(),
        merged,
        "full",
        0,
        List.of(),
        List.of(),
        false);
  }

  private void fetchTemplates() {
    try {
      fetchTemplatesInternal();
    } catch (RuntimeException e) {
      lastError = e;
      reportError(e);
    }
  }

  private void fetchTemplatesInternal() {
    TemplatesResult result = client.fetchTemplates(templateRevision);
    templateRevision = result.revision();
    if (!result.notModified() && onTemplates != null) {
      call(() -> onTemplates.accept(result.snapshot()));
    }
  }

  private Duration nextBackoff(Throwable error) {
    if (error instanceof ShieldXException shieldX
        && shieldX.retryAfter() != null
        && !shieldX.retryAfter().isZero()) {
      return shieldX.retryAfter().compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : shieldX.retryAfter();
    }
    Duration base;
    if (currentBackoff.compareTo(BASE_BACKOFF) < 0) {
      base = BASE_BACKOFF;
    } else {
      base = currentBackoff.multipliedBy(2);
      if (base.compareTo(MAX_BACKOFF) > 0) {
        base = MAX_BACKOFF;
      }
    }
    double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
    return Duration.ofMillis((long) (base.toMillis() * jitter));
  }

  private void reportError(Throwable error) {
    if (onError == null) {
      return;
    }
    try {
      onError.accept(error);
    } catch (RuntimeException ignored) {
      // A callback must never kill the polling loop.
    }
  }

  private void call(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      reportError(e);
    }
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Configuration for {@link Poller}. */
  public static final class Builder {
    private final ShieldXClient client;
    private Duration interval = DEFAULT_INTERVAL;
    private long initialRevision = -1;
    private boolean fetchTemplates;
    private Consumer<PolicySnapshot> onSnapshot;
    private LongConsumer onNotModified;
    private Consumer<TemplateSnapshot> onTemplates;
    private Consumer<Throwable> onError;

    private Builder(ShieldXClient client) {
      this.client = Objects.requireNonNull(client, "client");
    }

    public Builder interval(Duration value) {
      Objects.requireNonNull(value, "interval");
      if (value.compareTo(MIN_INTERVAL) < 0) {
        throw new IllegalArgumentException("interval must be at least 1s");
      }
      this.interval = value;
      return this;
    }

    public Builder initialRevision(long value) {
      if (value < -1) {
        throw new IllegalArgumentException("initialRevision must be >= -1");
      }
      this.initialRevision = value;
      return this;
    }

    public Builder fetchTemplates(boolean value) {
      this.fetchTemplates = value;
      return this;
    }

    public Builder onSnapshot(Consumer<PolicySnapshot> value) {
      this.onSnapshot = value;
      return this;
    }

    public Builder onNotModified(LongConsumer value) {
      this.onNotModified = value;
      return this;
    }

    public Builder onTemplates(Consumer<TemplateSnapshot> value) {
      this.onTemplates = value;
      return this;
    }

    public Builder onError(Consumer<Throwable> value) {
      this.onError = value;
      return this;
    }

    public Poller build() {
      return new Poller(this);
    }
  }
}
