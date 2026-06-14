package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;

/**
 * Primary port for the rate-limiting domain (hexagonal architecture).
 *
 * <p>Defines the single capability the system exposes: given a rule and the
 * current time, decide whether the request is allowed.
 *
 * <p>The interface is deliberately narrow — one method, no infrastructure
 * leakage. Callers (HTTP filters, gRPC interceptors, etc.) depend on this
 * abstraction, never on {@link SlidingWindowRateLimiter} directly. This
 * makes swapping the algorithm (e.g., Token Bucket on Day 3) a zero-change
 * operation for all callers.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>Implementations MUST be thread-safe — multiple request threads call
 *       this concurrently.</li>
 *   <li>Implementations MUST be atomic — check and record in a single
 *       operation (Lua script).</li>
 *   <li>{@code currentTimeMs} is passed in (not read internally) to keep
 *       implementations deterministic and trivially testable.</li>
 * </ul>
 */
public interface RateLimiterPort {

    /**
     * Checks whether a request is permitted under the given rule.
     *
     * <p>If allowed, the request is recorded and counts against the window.
     * If rejected, nothing is recorded — rejected requests do not consume quota.
     *
     * @param rule          the rate-limit rule defining the cap and window
     * @param currentTimeMs current epoch time in milliseconds; pass
     *                      {@code System.currentTimeMillis()} in production,
     *                      a fixed value in tests
     * @return a {@link RateLimitResult} describing the decision and
     *         the remaining capacity in the current window
     */
    RateLimitResult isAllowed(RateLimitRule rule, long currentTimeMs);
}
