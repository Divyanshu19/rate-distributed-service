package com.ratelimiter.distributed.core.model;

/**
 * Supported rate-limiting algorithms.
 *
 * <ul>
 *   <li>{@link #SLIDING_WINDOW} — tracks request timestamps within a rolling
 *       time window; provides smooth, accurate limiting with no boundary bursts.</li>
 *   <li>{@link #TOKEN_BUCKET}   — refills tokens at a fixed rate; allows short
 *       bursts up to bucket capacity while enforcing a long-term average rate.</li>
 * </ul>
 */
public enum Algorithm {

    /**
     * Sliding Window algorithm.
     * Each request is timestamped. Requests older than {@code windowSizeSeconds}
     * are evicted on every check. Accurate but slightly more Redis-expensive
     * (uses a sorted set per key).
     */
    SLIDING_WINDOW,

    /**
     * Token Bucket algorithm.
     * A bucket holds up to {@code maxRequests} tokens. Tokens are added at a
     * constant refill rate. Each request consumes one token. Allows bursting
     * up to the bucket size.
     */
    TOKEN_BUCKET
}
