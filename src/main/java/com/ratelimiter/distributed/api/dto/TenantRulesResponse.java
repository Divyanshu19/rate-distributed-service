package com.ratelimiter.distributed.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ratelimiter.distributed.core.model.Algorithm;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Response body for {@code GET /api/v1/ratelimit/rules/{tenantId}}.
 *
 * <p>Returns a structured view of all rules configured for the requested tenant,
 * along with a count for quick validation.
 *
 * <p>Example response (HTTP 200):
 * <pre>{@code
 * {
 *   "tenantId": "tesco-orders",
 *   "ruleCount": 2,
 *   "rules": [
 *     {
 *       "endpoint":          "/api/orders",
 *       "maxRequests":       50,
 *       "windowSizeSeconds": 60,
 *       "algorithm":         "SLIDING_WINDOW"
 *     },
 *     {
 *       "endpoint":          "/api/search",
 *       "maxRequests":       200,
 *       "windowSizeSeconds": 60,
 *       "algorithm":         "TOKEN_BUCKET"
 *     }
 *   ]
 * }
 * }</pre>
 */
@Value
@Builder
public class TenantRulesResponse {

    /** The tenant whose rules are listed. */
    @JsonProperty("tenantId")
    String tenantId;

    /** Total number of rules configured for this tenant. */
    @JsonProperty("ruleCount")
    int ruleCount;

    /** All rules configured for this tenant. */
    @JsonProperty("rules")
    List<RuleView> rules;

    /**
     * A sanitised, read-only projection of a {@link com.ratelimiter.distributed.core.model.RateLimitRule}.
     * Internal Redis key details are deliberately excluded.
     */
    @Value
    @Builder
    public static class RuleView {

        /** The endpoint path this rule governs. */
        @JsonProperty("endpoint")
        String endpoint;

        /** Maximum requests allowed in the window. */
        @JsonProperty("maxRequests")
        int maxRequests;

        /** Observation window in seconds. */
        @JsonProperty("windowSizeSeconds")
        int windowSizeSeconds;

        /** Algorithm applied to this rule. */
        @JsonProperty("algorithm")
        Algorithm algorithm;
    }
}
