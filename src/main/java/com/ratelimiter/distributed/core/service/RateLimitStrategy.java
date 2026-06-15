package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;

/**
 * Internal strategy contract implemented by every rate-limiting algorithm.
 *
 * <h2>Design — Strategy Pattern + Hexagonal Architecture</h2>
 * <pre>
 *   HTTP Layer
 *       │  depends on
 *       ▼
 *   RateLimiterPort          ← external-facing port (stable, never changes)
 *       │  implemented by
 *       ▼
 *   RateLimiterService       ← selects the right strategy per rule
 *       │  delegates to
 *       ▼
 *   RateLimitStrategy        ← this interface (internal, algorithm-level)
 *       │
 *       ├── SlidingWindowRateLimiter
 *       └── TokenBucketRateLimiter
 * </pre>
 *
 * <p>This two-level design means:
 * <ul>
 *   <li>Adding a new algorithm = create a new {@code @Service} that implements
 *       this interface. Zero changes to the HTTP layer or {@link RateLimiterPort}.</li>
 *   <li>{@link RateLimiterService} discovers all implementations automatically
 *       via Spring's {@code List<RateLimitStrategy>} injection.</li>
 *   <li>The algorithm is selected at runtime based on
 *       {@link RateLimitRule#getAlgorithm()}, not by if-else chains.</li>
 * </ul>
 *
 * <p><b>Implementation contract:</b>
 * <ul>
 *   <li>Must be thread-safe — called concurrently by many request threads.</li>
 *   <li>Must be atomic — check + record in a single Redis Lua script call.</li>
 *   <li>Must never read {@code System.currentTimeMillis()} internally —
 *       accept {@code currentTimeMs} as a parameter for testability.</li>
 * </ul>
 */
public interface RateLimitStrategy {

    /**
     * Evaluates whether the incoming request satisfies the rate-limit rule.
     *
     * <p>If allowed, the request is recorded atomically. If rejected, the
     * Redis state is NOT modified — rejected requests consume no quota.
     *
     * @param rule          the rule to enforce (cap, window, algorithm)
     * @param currentTimeMs epoch time in ms — injected by caller for testability
     * @return a rich {@link RateLimitResult} containing the decision,
     *         remaining capacity, and HTTP header values
     */
    RateLimitResult isAllowed(RateLimitRule rule, long currentTimeMs);

    /**
     * Declares which algorithm this strategy implements.
     *
     * <p>Used by {@link RateLimiterService} to build the strategy routing map
     * at startup. Must return a unique value per implementation.
     *
     * @return the {@link Algorithm} this class handles
     */
    Algorithm getSupportedAlgorithm();
}
