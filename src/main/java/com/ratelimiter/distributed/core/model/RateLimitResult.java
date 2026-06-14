package com.ratelimiter.distributed.core.model;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable result of a single rate-limit check.
 *
 * <p>Carries everything the HTTP layer (Day 3) needs to build a correct
 * response — both for allowed requests (standard headers) and rejected
 * requests (429 body + Retry-After header).
 *
 * <p>Standard HTTP rate-limit headers this enables:
 * <pre>
 *   X-RateLimit-Limit:     {@link #totalAllowed}
 *   X-RateLimit-Remaining: {@link #remainingRequests}
 *   X-RateLimit-Reset:     {@link #resetAtEpochMs} (as Unix epoch seconds)
 *   Retry-After:           {@link #retryAfterMs}   (only on 429 responses)
 * </pre>
 *
 * @see SlidingWindowRateLimiter for how this is produced
 */
@Value
@Builder
public class RateLimitResult {

    /**
     * Whether this specific request is permitted to proceed.
     * {@code true}  → allow the request through.
     * {@code false} → reject with HTTP 429.
     */
    boolean allowed;

    /**
     * How many more requests this tenant can make before hitting the limit.
     * Zero means this was the last permitted request in the current window.
     * Always 0 for rejected results.
     */
    int remainingRequests;

    /**
     * The total request cap defined by the rule.
     * Exposed directly so the HTTP layer can set {@code X-RateLimit-Limit}
     * without needing access to the original {@link RateLimitRule}.
     */
    int totalAllowed;

    /**
     * Approximate epoch time (ms) at which the current window will reset
     * and the tenant's full quota is restored.
     *
     * <p>Calculated as: {@code currentTimeMs + windowSizeMs}.
     * This is a conservative upper bound — the actual reset may be slightly
     * earlier depending on when the oldest request in the window ages out.
     */
    long resetAtEpochMs;

    /**
     * How many milliseconds the caller should wait before retrying.
     * Only meaningful when {@link #allowed} is {@code false}.
     * Calculated as: {@code resetAtEpochMs - currentTimeMs}.
     */
    long retryAfterMs;

    // ── Static factory methods — prefer these over the raw builder ──────────

    /**
     * Creates an ALLOWED result.
     *
     * @param remaining     slots left in the window after this request
     * @param totalAllowed  the rule's max-requests cap
     * @param resetAtMs     epoch ms when the window fully resets
     * @return an allowed {@link RateLimitResult}
     */
    public static RateLimitResult allowed(int remaining, int totalAllowed, long resetAtMs) {
        return RateLimitResult.builder()
                .allowed(true)
                .remainingRequests(remaining)
                .totalAllowed(totalAllowed)
                .resetAtEpochMs(resetAtMs)
                .retryAfterMs(0L)
                .build();
    }

    /**
     * Creates a REJECTED result.
     *
     * @param totalAllowed  the rule's max-requests cap
     * @param resetAtMs     epoch ms when the window fully resets
     * @param currentTimeMs the time the check was performed
     * @return a rejected {@link RateLimitResult}
     */
    public static RateLimitResult rejected(int totalAllowed, long resetAtMs, long currentTimeMs) {
        return RateLimitResult.builder()
                .allowed(false)
                .remainingRequests(0)
                .totalAllowed(totalAllowed)
                .resetAtEpochMs(resetAtMs)
                .retryAfterMs(resetAtMs - currentTimeMs)
                .build();
    }
}
