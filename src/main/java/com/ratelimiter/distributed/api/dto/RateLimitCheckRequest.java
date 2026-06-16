package com.ratelimiter.distributed.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Request body for {@code POST /api/v1/ratelimit/check}.
 *
 * <p>All three fields are mandatory. A missing or blank value for any of them
 * will trigger a {@code 400 Bad Request} response from the global exception
 * handler before the rate-limit logic is even invoked.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "tenantId":  "tesco-orders",
 *   "endpoint":  "/api/orders",
 *   "requestId": "550e8400-e29b-41d4-a716-446655440000"
 * }
 * }</pre>
 *
 * <p>{@code requestId} is logged and carried through for distributed tracing
 * but does not affect the rate-limit decision.
 */
@Value
@Jacksonized
@lombok.Builder
public class RateLimitCheckRequest {

    /**
     * Identifier of the calling tenant.
     * Must match a key in the rule registry (e.g. {@code tesco-orders}).
     */
    @NotBlank(message = "tenantId must not be blank")
    @JsonProperty("tenantId")
    String tenantId;

    /**
     * The API endpoint path being checked (e.g. {@code /api/orders}).
     * Must match an endpoint registered for the given tenant.
     */
    @NotBlank(message = "endpoint must not be blank")
    @JsonProperty("endpoint")
    String endpoint;

    /**
     * Caller-supplied correlation / idempotency ID (UUID recommended).
     * Logged for tracing purposes; does not influence the rate-limit decision.
     */
    @NotBlank(message = "requestId must not be blank")
    @JsonProperty("requestId")
    String requestId;
}
