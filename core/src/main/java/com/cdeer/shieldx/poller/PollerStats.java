package com.cdeer.shieldx.poller;

import java.time.Duration;
import java.time.Instant;

/** Immutable poller statistics. */
public record PollerStats(
    long polls,
    long success,
    long notModified,
    long failures,
    long lastRevision,
    Instant lastSuccess,
    Throwable lastError,
    Duration currentBackoff) {}
