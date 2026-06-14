package com.ratelimiter.distributed.core.model;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable domain model representing a single rate-limit rule.
 *
 * <p>A rule is scoped to a specific tenant and endpoint combination.
 * The same tenant can have different limits on different endpoints,
 * and different tenants can have completely independent rule sets.
 *
 * <p>Example:
 * <pre>{@code
 * RateLimitRule rule = RateLimitRule.builder()
 *     .tenantId("tenant-acme")
 *     .endpoint("/api/v1/orders")
 *     .maxRequests(100)
 *     .windowSizeSeconds(60)
 *     .algorithm(Algorithm.SLIDING_WINDOW)
 *     .build();
 * }</pre>
 *
 * <p><b>Redis key convention:</b> {@code rate_limit:{tenantId}:{endpoint}}
 * <br>Example: {@code rate_limit:tenant-acme:/api/v1/orders}
 */
@Value                  // Lombok: final fields, all-args constructor, getters, equals/hashCode/toString
@Builder(toBuilder = true)
public class RateLimitRule {

    /**
     * Unique identifier for the tenant (client/consumer of the API).
     * Used as the first segment of the Redis key.
     * Must not be null or blank.
     */
    String tenantId;

    /**
     * The API endpoint this rule applies to.
     * Should be a normalised path, e.g. {@code /api/v1/search}.
     * Used as the second segment of the Redis key.
     */
    String endpoint;

    /**
     * Maximum number of requests permitted within the time window.
     * For Token Bucket this doubles as the bucket capacity.
     * Must be > 0.
     */
    int maxRequests;

    /**
     * Duration of the observation window in seconds.
     * For Sliding Window: the look-back period.
     * For Token Bucket: the full-refill period.
     * Must be > 0.
     */
    int windowSizeSeconds;

    /**
     * The rate-limiting algorithm to apply for this rule.
     * Defaults to {@link Algorithm#SLIDING_WINDOW} for accuracy.
     * @see Algorithm
     */
    @Builder.Default
    Algorithm algorithm = Algorithm.SLIDING_WINDOW;

    /**
     * Derives the Redis key for this rule.
     * Format: {@code rate_limit:{tenantId}:{endpoint}}
     *
     * @return the Redis key string
     */
    public String redisKey() {
        return "rate_limit:" + tenantId + ":" + endpoint;
    }
}
