package com.cdeer.shieldx.spring;

import com.cdeer.shieldx.client.Format;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the shield-x starter. */
@ConfigurationProperties(prefix = "shield-x")
public class ShieldXProperties {

  private boolean enabled = true;
  private String baseUrl;
  private String token;
  private String gatewayId;
  private String agentVersion;
  private Duration connectTimeout = Duration.ofSeconds(3);
  private Duration requestTimeout = Duration.ofSeconds(10);
  private Capabilities capabilities = new Capabilities();
  private Poll poll = new Poll();
  private Report report = new Report();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getGatewayId() {
    return gatewayId;
  }

  public void setGatewayId(String gatewayId) {
    this.gatewayId = gatewayId;
  }

  public String getAgentVersion() {
    return agentVersion;
  }

  public void setAgentVersion(String agentVersion) {
    this.agentVersion = agentVersion;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Capabilities getCapabilities() {
    return capabilities;
  }

  public void setCapabilities(Capabilities capabilities) {
    this.capabilities = capabilities;
  }

  public Poll getPoll() {
    return poll;
  }

  public void setPoll(Poll poll) {
    this.poll = poll;
  }

  public Report getReport() {
    return report;
  }

  public void setReport(Report report) {
    this.report = report;
  }

  /** Capability declaration. Empty actions means "do not send capabilities". */
  public static class Capabilities {
    private List<String> actions = new ArrayList<>(List.of("observe"));
    private List<String> matchFields = new ArrayList<>(List.of("ip"));
    private boolean blockTemplates;
    private int templateSpecVersion;
    private int maxRules = 5000;
    private boolean supportsRateLimit;

    public List<String> getActions() {
      return actions;
    }

    public void setActions(List<String> actions) {
      this.actions = actions;
    }

    public List<String> getMatchFields() {
      return matchFields;
    }

    public void setMatchFields(List<String> matchFields) {
      this.matchFields = matchFields;
    }

    public boolean isBlockTemplates() {
      return blockTemplates;
    }

    public void setBlockTemplates(boolean blockTemplates) {
      this.blockTemplates = blockTemplates;
    }

    public int getTemplateSpecVersion() {
      return templateSpecVersion;
    }

    public void setTemplateSpecVersion(int templateSpecVersion) {
      this.templateSpecVersion = templateSpecVersion;
    }

    public int getMaxRules() {
      return maxRules;
    }

    public void setMaxRules(int maxRules) {
      this.maxRules = maxRules;
    }

    public boolean isSupportsRateLimit() {
      return supportsRateLimit;
    }

    public void setSupportsRateLimit(boolean supportsRateLimit) {
      this.supportsRateLimit = supportsRateLimit;
    }
  }

  /** Poller settings. */
  public static class Poll {
    private boolean enabled = true;
    private Duration interval = Duration.ofSeconds(5);
    private long initialRevision = -1;
    private boolean fetchTemplates;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public Duration getInterval() {
      return interval;
    }

    public void setInterval(Duration interval) {
      this.interval = interval;
    }

    public long getInitialRevision() {
      return initialRevision;
    }

    public void setInitialRevision(long initialRevision) {
      this.initialRevision = initialRevision;
    }

    public boolean isFetchTemplates() {
      return fetchTemplates;
    }

    public void setFetchTemplates(boolean fetchTemplates) {
      this.fetchTemplates = fetchTemplates;
    }
  }

  /** Reporter settings. */
  public static class Report {
    private boolean enabled = true;
    private int batchSize = 300;
    private Duration flushInterval = Duration.ofSeconds(2);
    private int queueSize = 10_000;
    private String spoolDir;
    private Format format = Format.NDJSON;
    private boolean gzip = true;
    private Duration maxBackoff = Duration.ofSeconds(60);
    private Duration shutdownTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }

    public Duration getFlushInterval() {
      return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
      this.flushInterval = flushInterval;
    }

    public int getQueueSize() {
      return queueSize;
    }

    public void setQueueSize(int queueSize) {
      this.queueSize = queueSize;
    }

    public String getSpoolDir() {
      return spoolDir;
    }

    public void setSpoolDir(String spoolDir) {
      this.spoolDir = spoolDir;
    }

    public Format getFormat() {
      return format;
    }

    public void setFormat(Format format) {
      this.format = format;
    }

    public boolean isGzip() {
      return gzip;
    }

    public void setGzip(boolean gzip) {
      this.gzip = gzip;
    }

    public Duration getMaxBackoff() {
      return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
      this.maxBackoff = maxBackoff;
    }

    public Duration getShutdownTimeout() {
      return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
      this.shutdownTimeout = shutdownTimeout;
    }
  }
}
