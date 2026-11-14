package com.cdeer.shieldx.spring;

import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.reporter.Reporter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;

/** Low-cardinality Micrometer gauges for the poller and reporter. */
public final class ShieldXMetrics implements MeterBinder {

  private final ObjectProvider<Poller> pollers;
  private final ObjectProvider<Reporter> reporters;

  ShieldXMetrics(ObjectProvider<Poller> pollers, ObjectProvider<Reporter> reporters) {
    this.pollers = pollers;
    this.reporters = reporters;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Poller poller = pollers.getIfAvailable();
    if (poller != null) {
      Gauge.builder("shieldx.poll.polls", poller, p -> p.stats().polls()).register(registry);
      Gauge.builder("shieldx.poll.success", poller, p -> p.stats().success()).register(registry);
      Gauge.builder("shieldx.poll.not_modified", poller, p -> p.stats().notModified())
          .register(registry);
      Gauge.builder("shieldx.poll.failures", poller, p -> p.stats().failures()).register(registry);
      Gauge.builder("shieldx.poll.last_revision", poller, p -> p.stats().lastRevision())
          .register(registry);
      Gauge.builder(
              "shieldx.poll.last_success_epoch_seconds",
              poller,
              p -> {
                Instant last = p.stats().lastSuccess();
                return last == null ? 0 : last.getEpochSecond();
              })
          .register(registry);
    }
    Reporter reporter = reporters.getIfAvailable();
    if (reporter != null) {
      Gauge.builder("shieldx.report.queue.depth", reporter, r -> r.stats().queueDepth())
          .register(registry);
      Gauge.builder("shieldx.report.dropped", reporter, r -> r.stats().dropped())
          .register(registry);
      Gauge.builder("shieldx.report.accepted", reporter, r -> r.stats().accepted())
          .register(registry);
      Gauge.builder("shieldx.report.rejected", reporter, r -> r.stats().rejected())
          .register(registry);
      Gauge.builder("shieldx.report.sent", reporter, r -> r.stats().sent()).register(registry);
      Gauge.builder("shieldx.report.spool.files", reporter, r -> r.stats().spoolFiles())
          .register(registry);
      Gauge.builder("shieldx.report.spool.bytes", reporter, r -> r.stats().spoolBytes())
          .register(registry);
      Gauge.builder("shieldx.report.replayed", reporter, r -> r.stats().replayed())
          .register(registry);
    }
  }
}
