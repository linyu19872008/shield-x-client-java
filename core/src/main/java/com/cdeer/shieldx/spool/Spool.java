package com.cdeer.shieldx.spool;

import com.cdeer.shieldx.model.EventBatch;
import java.io.IOException;

/** Optional reporter buffer. Implementations must tolerate concurrent stats() calls. */
public interface Spool extends AutoCloseable {

  void save(EventBatch batch) throws IOException;

  /**
   * Replays files in oldest-first order. The callback's exception stops replay and keeps the file.
   */
  void replay(ReplayCallback callback) throws Exception;

  SpoolStats stats();

  void trim() throws IOException;

  @Override
  default void close() throws IOException {}

  /** Callback invoked for one persisted batch. */
  @FunctionalInterface
  interface ReplayCallback {
    void accept(EventBatch batch) throws Exception;
  }
}
