package com.cdeer.shieldx.client;

import java.time.Instant;

/** Result of an event report. {@code serverTime} may be null when the server omits it. */
public record ReportResult(int accepted, int rejected, Instant serverTime) {}
