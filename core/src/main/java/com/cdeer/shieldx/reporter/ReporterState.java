package com.cdeer.shieldx.reporter;

/** Revision and policy count attached to every batch. */
public record ReporterState(long revision, int policyCount) {}
