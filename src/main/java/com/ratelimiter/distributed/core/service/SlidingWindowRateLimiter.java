package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Sliding Window rate limiter backed by Redis.
 *
 * <h2>Algorithm</h2>
 * <pre>
 *   Time ──────────────────────────────────────────────▶
 *
 *         │◄──── windowSize (e.g. 60s) ────►│
 *         │                                 │
 *   ──────●────●──────●────●────●──────●────[NOW]────▶
 *         t1   t2     t3   t4   t5     t6
 *
 *   Entries are stored in a Redis Sorted Set:
 *     score  = request timestamp (ms)
 *     member = unique request ID
 *
 *   On every call:
 *     1. ZREMRANGEBYSCORE: evict entries with score ≤ (now - windowSize)
 *     2. ZCARD: count remaining entries
 *     3. If count < maxRequests: ZADD + EXPIRE → allowed
 *        Else:                   return -1      → rejected
 *
 *   All three steps run inside a single Lua script → atomic.
 * </pre>
 *
 * <h2>Concurrency safety</h2>
 * <p>The Lua script executes atomically on the Redis server. Two threads
 * cannot both pass the {@code ZCARD < maxRequests} check simultaneously —
 * Redis is single-threaded per command execution and Lua scripts are
 * non-preemptable. This eliminates the TOCTOU (Time-Of-Check-Time-Of-Use)
 * race condition that would exist with separate check + write calls.
 *
 * <h2>Member uniqueness</h2>
 * <p>ZSET members must be unique. Using the raw timestamp as the member
 * would cause same-millisecond requests to silently overwrite each other
 * (Redis ZADD updates the score for an existing member). We append a UUID
 * to guarantee uniqueness regardless of concurrency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlidingWindowRateLimiter implements RateLimiterPort {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> slidingWindowScript;

    // TTL multiplier: keep the key alive for 2× the window after the last request.
    // Prevents stale keys from lingering if a tenant goes quiet for a long time.
    private static final int TTL_MULTIPLIER = 2;

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the Lua script running atomically in Redis.
     * The script returns remaining capacity (≥ 0) if allowed, or -1 if rejected.
     *
     * @param rule          the rate-limit rule to enforce
     * @param currentTimeMs caller-supplied time (ms); injectable for testing
     */
    @Override
    public RateLimitResult isAllowed(RateLimitRule rule, long currentTimeMs) {

        String redisKey     = rule.redisKey();
        long windowSizeMs   = rule.getWindowSizeSeconds() * 1000L;
        long windowStartMs  = currentTimeMs - windowSizeMs;
        long ttlSeconds     = (long) rule.getWindowSizeSeconds() * TTL_MULTIPLIER;
        long resetAtMs      = currentTimeMs + windowSizeMs;

        // Unique member: timestamp prefix aids redis-cli debugging,
        // UUID suffix guarantees no collision under concurrent load.
        String member = currentTimeMs + "-" + UUID.randomUUID();

        log.debug("Rate limit check — key={} tenantId={} endpoint={} window={}s",
                redisKey, rule.getTenantId(), rule.getEndpoint(), rule.getWindowSizeSeconds());

        Long scriptResult = redisTemplate.execute(
                slidingWindowScript,
                List.of(redisKey),
                String.valueOf(currentTimeMs),  // ARGV[1] — now
                String.valueOf(windowStartMs),  // ARGV[2] — window start (exclusive lower bound)
                String.valueOf(rule.getMaxRequests()), // ARGV[3] — cap
                String.valueOf(ttlSeconds),     // ARGV[4] — TTL
                member                          // ARGV[5] — unique request ID
        );

        return buildResult(scriptResult, rule, resetAtMs, currentTimeMs);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Converts the raw Lua return value into a {@link RateLimitResult}.
     *
     * <p>Defensive null handling covers the case where Redis returns {@code null}
     * (e.g., the script was flushed, network blip). We treat null as a rejection
     * to fail closed — safer than accidentally allowing unlimited requests.
     *
     * @param scriptResult  raw value from Lua: ≥0 = allowed, -1 = rejected, null = error
     * @param rule          original rule (for cap and context)
     * @param resetAtMs     pre-computed window reset time
     * @param currentTimeMs time the check was performed
     */
    private RateLimitResult buildResult(Long scriptResult,
                                        RateLimitRule rule,
                                        long resetAtMs,
                                        long currentTimeMs) {
        if (scriptResult == null) {
            log.warn("Lua script returned null for key={}. Failing closed (reject).", rule.redisKey());
            return RateLimitResult.rejected(rule.getMaxRequests(), resetAtMs, currentTimeMs);
        }

        if (scriptResult >= 0) {
            log.debug("ALLOWED — remaining={} key={}", scriptResult, rule.redisKey());
            return RateLimitResult.allowed(
                    scriptResult.intValue(),
                    rule.getMaxRequests(),
                    resetAtMs
            );
        }

        log.debug("REJECTED — key={} limit={}", rule.redisKey(), rule.getMaxRequests());
        return RateLimitResult.rejected(rule.getMaxRequests(), resetAtMs, currentTimeMs);
    }
}
