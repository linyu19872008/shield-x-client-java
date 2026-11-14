package com.cdeer.shieldx.spring;

import com.cdeer.shieldx.client.Capabilities;
import com.cdeer.shieldx.client.ShieldXClient;
import com.cdeer.shieldx.model.PolicySnapshot;
import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.reporter.Reporter;
import com.cdeer.shieldx.reporter.ReporterState;
import com.cdeer.shieldx.spool.FileSpool;
import com.cdeer.shieldx.spool.Spool;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for the shield-x client, poller, reporter and spool. */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "shield-x",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(ShieldXProperties.class)
public class ShieldXAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ShieldXAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public ShieldXClient shieldXClient(ShieldXProperties properties) {
    ShieldXClient.Builder builder =
        ShieldXClient.builder()
            .baseUrl(properties.getBaseUrl())
            .token(properties.getToken())
            .gatewayId(properties.getGatewayId())
            .agentVersion(properties.getAgentVersion())
            .connectTimeout(properties.getConnectTimeout())
            .requestTimeout(properties.getRequestTimeout());
    ShieldXProperties.Capabilities caps = properties.getCapabilities();
    if (caps != null && caps.getActions() != null && !caps.getActions().isEmpty()) {
      builder.capabilities(
          Capabilities.builder()
              .actions(List.copyOf(caps.getActions()))
              .matchFields(List.copyOf(caps.getMatchFields()))
              .blockTemplates(caps.isBlockTemplates())
              .templateSpecVersion(caps.getTemplateSpecVersion())
              .maxRules(caps.getMaxRules())
              .supportsRateLimit(caps.isSupportsRateLimit())
              .build());
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "shield-x.poll",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Poller shieldXPoller(ShieldXClient client, ShieldXProperties properties) {
    ShieldXProperties.Poll poll = properties.getPoll();
    return Poller.builder(client)
        .interval(poll.getInterval())
        .initialRevision(poll.getInitialRevision())
        .fetchTemplates(poll.isFetchTemplates())
        .onError(error -> LOG.warn("shield-x poll failed: {}", error.getMessage()))
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "shield-x.report", name = "spool-dir", matchIfMissing = false)
  public FileSpool shieldXSpool(ShieldXProperties properties) {
    return new FileSpool(Path.of(properties.getReport().getSpoolDir()));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "shield-x.report",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public Reporter shieldXReporter(
      ShieldXClient client,
      ShieldXProperties properties,
      ObjectProvider<Poller> pollerProvider,
      ObjectProvider<Spool> spoolProvider) {
    ShieldXProperties.Report report = properties.getReport();
    Poller poller = pollerProvider.getIfAvailable();
    return Reporter.builder(client)
        .batchSize(report.getBatchSize())
        .flushInterval(report.getFlushInterval())
        .queueSize(report.getQueueSize())
        .format(report.getFormat())
        .gzip(report.isGzip())
        .maxBackoff(report.getMaxBackoff())
        .shutdownTimeout(report.getShutdownTimeout())
        .spool(spoolProvider.getIfAvailable())
        .state(() -> stateOf(poller))
        .onError(error -> LOG.warn("shield-x report failed: {}", error.getMessage()))
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  public ShieldXLifecycle shieldXLifecycle(
      ObjectProvider<Poller> pollerProvider, ObjectProvider<Reporter> reporterProvider) {
    return new ShieldXLifecycle(pollerProvider, reporterProvider);
  }

  @Bean
  @ConditionalOnClass(HealthIndicator.class)
  @ConditionalOnMissingBean(name = "shieldXHealthIndicator")
  public ShieldXHealthIndicator shieldXHealthIndicator(
      ObjectProvider<Poller> pollerProvider, ObjectProvider<Reporter> reporterProvider) {
    return new ShieldXHealthIndicator(pollerProvider, reporterProvider);
  }

  @Bean
  @ConditionalOnClass(MeterBinder.class)
  @ConditionalOnMissingBean(name = "shieldXMetrics")
  public ShieldXMetrics shieldXMetrics(
      ObjectProvider<Poller> pollerProvider, ObjectProvider<Reporter> reporterProvider) {
    return new ShieldXMetrics(pollerProvider, reporterProvider);
  }

  private static ReporterState stateOf(Poller poller) {
    if (poller == null) {
      return new ReporterState(0, 0);
    }
    PolicySnapshot snapshot = poller.snapshot();
    return snapshot == null
        ? new ReporterState(0, 0)
        : new ReporterState(snapshot.revision(), snapshot.policies().size());
  }
}
