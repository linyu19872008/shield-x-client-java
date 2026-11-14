package com.cdeer.shieldx.spring;

import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.poller.PollerStats;
import com.cdeer.shieldx.reporter.Reporter;
import com.cdeer.shieldx.reporter.ReporterStats;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Reports poll/report freshness without taking the gateway out of rotation on transient errors. */
public final class ShieldXHealthIndicator implements HealthIndicator {

  private final ObjectProvider<Poller> pollers;
  private final ObjectProvider<Reporter> reporters;

  ShieldXHealthIndicator(ObjectProvider<Poller> pollers, ObjectProvider<Reporter> reporters) {
    this.pollers = pollers;
    this.reporters = reporters;
  }

  @Override
  public Health health() {
    Health.Builder builder = Health.up();
    Poller poller = pollers.getIfAvailable();
    if (poller != null) {
      PollerStats stats = poller.stats();
      builder
          .withDetail("lastRevision", stats.lastRevision())
          .withDetail("polls", stats.polls())
          .withDetail("notModified", stats.notModified())
          .withDetail("failures", stats.failures())
          .withDetail("stale", stats.lastSuccess() == null && stats.polls() > 0);
      if (stats.lastSuccess() != null) {
        builder.withDetail("lastSuccess", stats.lastSuccess().toString());
      }
      if (stats.lastError() != null) {
        builder.withDetail("lastError", stats.lastError().getMessage());
      }
    }
    Reporter reporter = reporters.getIfAvailable();
    if (reporter != null) {
      ReporterStats stats = reporter.stats();
      builder
          .withDetail("queueDepth", stats.queueDepth())
          .withDetail("dropped", stats.dropped())
          .withDetail("spoolFiles", stats.spoolFiles())
          .withDetail("spoolBytes", stats.spoolBytes());
      if (stats.lastError() != null) {
        builder.withDetail("reportLastError", stats.lastError().getMessage());
      }
    }
    return builder.build();
  }
}
