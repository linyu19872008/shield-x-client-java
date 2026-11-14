package com.cdeer.shieldx.reporter;

import java.time.Instant;

/** Immutable reporter statistics. */
public record ReporterStats(
    long enqueued,
    long dropped,
    long sent,
    long accepted,
    long rejected,
    long retried,
    long spooled,
    long replayed,
    int queueDepth,
    int spoolFiles,
    long spoolBytes,
    Throwable lastError,
    Instant lastSuccess) {}
