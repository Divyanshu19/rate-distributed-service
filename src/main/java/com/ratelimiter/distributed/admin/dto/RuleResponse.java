package com.ratelimiter.distributed.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ratelimiter.distributed.core.model.Algorithm;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Response body returned by all {@code /admin/rules} endpoints.
 *
 * <p>This is a full view of the persisted rule, including its database ID
 * and audit timestamps. Clients use the {@code id} to address
 * {@code PUT /admin/rules/{id}} and {@code DELETE /admin/rules/{id}}.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "id":                1,
 *   "tenantId":          "tesco-orders",
 *   "endpoint":          "/api/orders",
 *   "maxRequests":       100,
 *   "windowSizeSeconds": 60,
 *   "algorithm":         "SLIDING_WINDOW",
 *   "createdAt":         "2024-11-14T12:00:00Z",
 *   "updatedAt":         "2024-11-14T12:05:00Z"
 * }
 * }</pre>
 */
@Value
@Builder
public class RuleResponse {

    /** Database-assigned ID — use this in PUT/DELETE URLs. */
    @JsonProperty("id")
    Long id;

    @JsonProperty("tenantId")
    String tenantId;

    @JsonProperty("endpoint")
    String endpoint;

    @JsonProperty("maxRequests")
    int maxRequests;

    @JsonProperty("windowSizeSeconds")
    int windowSizeSeconds;

    @JsonProperty("algorithm")
    Algorithm algorithm;

    /** ISO-8601 timestamp of when the rule was first created. */
    @JsonProperty("createdAt")
    Instant createdAt;

    /** ISO-8601 timestamp of the last update. */
    @JsonProperty("updatedAt")
    Instant updatedAt;
}
