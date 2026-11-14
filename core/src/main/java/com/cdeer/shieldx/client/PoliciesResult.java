package com.cdeer.shieldx.client;

import com.cdeer.shieldx.model.PolicySnapshot;

/** Result of a policy pull. On 304, {@code snapshot} is null and {@code notModified} is true. */
public record PoliciesResult(long revision, boolean notModified, PolicySnapshot snapshot) {}
