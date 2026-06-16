package com.ratelimiter.distributed.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * Response body for {@code POST /api/v1/ratelimit/check}.
 *
 * <p>Allowed response (HTTP 200):
 * <pre>{@code
 * {
 *   "allowed":           true,
 *   "remainingRequests": 45,
 *   "resetAfterMs":      3000,
 *   "retryAfterMs":      null
 * }
 * }</pre>
 *
 * <p>Rejected response (HTTP 429):
 * <pre>{@code
 * {
 *   "allowed":           false,
 *   "remainingRequests": 0,
 *   "resetAfterMs":      3000,
 *   "retryAfterMs":      3000
 * }
 * }</pre>
 *
 * <p>{@code retryAfterMs} is {@code null} when the request is allowed,
 * and is omitted from the JSON output via {@link JsonInclude#NON_NULL}.
 *
 * <p>In addition to this body, the HTTP response carries three standard headers:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — total cap defined by the rule</li>
 *   <li>{@code X-RateLimit-Remaining} — slots left in the current window</li>
 *   <li>{@code X-RateLimit-Reset}     — Unix epoch seconds when the window resets</li>
 * </ul>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitCheckResponse {

    /** Whether this request is permitted to proceed. */
    @JsonProperty("allowed")
    boolean allowed;

    /**
     * How many more requests are permitted in the current window.
     * Always {@code 0} for rejected responses.
     */
    @JsonProperty("remainingRequests")
    int remainingRequests;

    /**
     * Milliseconds until the current window resets and the full quota is restored.
     * Calculated as {@code resetAtEpochMs - currentTimeMs}.
     */
    @JsonProperty("resetAfterMs")
    long resetAfterMs;

    /**
     * Milliseconds the caller should wait before retrying.
     * Present only when {@code allowed == false}; {@code null} otherwise.
     * Serialised as {@code null} in JSON and omitted when {@code null}.
     */
    @JsonProperty("retryAfterMs")
    Long retryAfterMs;
}
