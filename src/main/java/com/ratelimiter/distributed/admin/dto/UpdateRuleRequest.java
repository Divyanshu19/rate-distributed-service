package com.ratelimiter.distributed.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ratelimiter.distributed.core.model.Algorithm;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Request body for {@code PUT /admin/rules/{id}}.
 *
 * <p>All fields are required — this is a full replacement (PUT semantics),
 * not a partial update (PATCH). Callers must send the complete rule.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "maxRequests":       200,
 *   "windowSizeSeconds": 60,
 *   "algorithm":         "TOKEN_BUCKET"
 * }
 * }</pre>
 *
 * <p>{@code tenantId} and {@code endpoint} cannot be changed on an existing rule.
 * To re-key a rule, delete and recreate it.
 */
@Value
@Builder
@Jacksonized
public class UpdateRuleRequest {

    @Min(value = 1, message = "maxRequests must be at least 1")
    @JsonProperty("maxRequests")
    int maxRequests;

    @Min(value = 1, message = "windowSizeSeconds must be at least 1")
    @JsonProperty("windowSizeSeconds")
    int windowSizeSeconds;

    @NotNull(message = "algorithm must not be null")
    @JsonProperty("algorithm")
    Algorithm algorithm;
}
