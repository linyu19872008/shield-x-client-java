package com.cdeer.shieldx.spring;

import com.cdeer.shieldx.poller.Poller;
import com.cdeer.shieldx.reporter.Reporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

/** Starts poller before reporter and stops reporter before poller. */
public final class ShieldXLifecycle implements SmartLifecycle {

  private final ObjectProvider<Poller> pollers;
  private final ObjectProvider<Reporter> reporters;
  private volatile boolean running;

  ShieldXLifecycle(ObjectProvider<Poller> pollers, ObjectProvider<Reporter> reporters) {
    this.pollers = pollers;
    this.reporters = reporters;
  }

  @Override
  public void start() {
    Poller poller = pollers.getIfAvailable();
    if (poller != null) {
      poller.start();
    }
    Reporter reporter = reporters.getIfAvailable();
    if (reporter != null) {
      reporter.start();
    }
    running = true;
  }

  @Override
  public void stop() {
    Reporter reporter = reporters.getIfAvailable();
    if (reporter != null) {
      reporter.close();
    }
    Poller poller = pollers.getIfAvailable();
    if (poller != null) {
      poller.close();
    }
    running = false;
  }

  @Override
  public void stop(Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
