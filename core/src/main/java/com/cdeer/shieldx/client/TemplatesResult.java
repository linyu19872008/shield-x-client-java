package com.cdeer.shieldx.client;

import com.cdeer.shieldx.model.TemplateSnapshot;

/** Result of a template pull. On 304, {@code snapshot} is null and {@code notModified} is true. */
public record TemplatesResult(long revision, boolean notModified, TemplateSnapshot snapshot) {}
