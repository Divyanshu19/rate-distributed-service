package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Token Bucket rate limiter backed by Redis.
 *
 * <h2>Algorithm</h2>
 * <pre>
 *   Bucket capacity = maxRequests  (e.g. 10)
 *   Refill rate     = maxRequests / (windowSizeSeconds × 1000) tokens/ms
 *
 *   t=0s   ▓▓▓▓▓▓▓▓▓▓  bucket full (10 tokens)
 *   t=0s   ░▓▓▓▓▓▓▓▓▓  request 1 consumed 1 token (9 remaining)
 *   t=0s   ░░▓▓▓▓▓▓▓▓  request 2 consumed 1 token (8 remaining)
 *   ...
 *   t=0s   ░░░░░░░░░░  all 10 tokens consumed (burst absorbed)
 *   t=6s   ░░░░░░░░░▓  1 token refilled (10 req / 60s = 1 token per 6s)
 *   t=6s   ░░░░░░░░░░  request 11 consumes it (0 remaining)
 *   t=12s  ░░░░░░░░░▓  another token refilled → next request allowed
 * </pre>
 *
 * <h2>Key differences from Sliding Window</h2>
 * <table>
 *   <tr><th>Property</th><th>Sliding Window</th><th>Token Bucket</th></tr>
 *   <tr><td>Burst tolerance</td><td>None — smooth rate only</td><td>Up to bucket capacity</td></tr>
 *   <tr><td>Redis structure</td><td>ZSET (one entry per request)</td><td>HASH (2 fields total)</td></tr>
 *   <tr><td>Memory per key</td><td>O(maxRequests)</td><td>O(1) — always 2 fields</td></tr>
 *   <tr><td>Best for</td><td>Strict API quotas</td><td>Bursty clients (mobile, batch)</td></tr>
 * </table>
 *
 * <h2>Refill rate calculation</h2>
 * <pre>
 *   refillRatePerMs = maxRequests / (windowSizeSeconds × 1000)
 *
 *   Example: rule = 10 req / 60s
 *   refillRatePerMs = 10 / 60_000 = 0.0001667 tokens per millisecond
 *   After 6000ms:   tokensAdded = 6000 × 0.0001667 = 1.0 token
 *   After 30000ms:  tokensAdded = 30000 × 0.0001667 = 5.0 tokens
 * </pre>
 *
 * <h2>Atomicity</h2>
 * <p>All read-refill-decide-write steps run inside a single Lua script.
 * Redis cannot interleave another command between them — no race conditions.
 */
@Slf4j
@Service
public class TokenBucketRateLimiter implements RateLimitStrategy {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> tokenBucketScript;

    // TTL = 2× window so keys expire cleanly after a period of inactivity.
    private static final int TTL_MULTIPLIER = 2;

    /**
     * @param redisTemplate    the shared Redis client
     * @param tokenBucketScript injected by bean name to avoid ambiguity with
     *                          the {@code slidingWindowScript} bean of the same type
     */
    public TokenBucketRateLimiter(RedisTemplate<String, String> redisTemplate,
                                  @Qualifier("tokenBucketScript")
                                  DefaultRedisScript<Long> tokenBucketScript) {
        this.redisTemplate   = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    @Override
    public Algorithm getSupportedAlgorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    /**
     * Checks whether a request can consume one token from the bucket.
     *
     * <p>The refill rate is derived from the rule's {@code maxRequests} and
     * {@code windowSizeSeconds}. The bucket starts full on the first request
     * for a given key (full burst allowance from the start).
     *
     * @param rule          defines capacity, refill window, and the Redis key
     * @param currentTimeMs caller-supplied time in ms — never read internally
     */
    @Override
    public RateLimitResult isAllowed(RateLimitRule rule, long currentTimeMs) {

        String redisKey        = rule.redisKey();
        long   ttlSeconds      = (long) rule.getWindowSizeSeconds() * TTL_MULTIPLIER;

        // Refill rate: how many tokens accumulate per millisecond.
        // Kept as a high-precision double and passed as a string to Lua
        // to avoid floating-point precision loss from integer division.
        double refillRatePerMs = (double) rule.getMaxRequests()
                                 / ((double) rule.getWindowSizeSeconds() * 1000.0);

        // Approximate reset time: how long until one full token refills.
        // Used for Retry-After header on rejected responses.
        long msPerToken = (long) Math.ceil(1.0 / refillRatePerMs);
        long resetAtMs  = currentTimeMs + msPerToken;

        log.debug("Token bucket check — key={} capacity={} refillRate={}/ms",
                redisKey, rule.getMaxRequests(), refillRatePerMs);

        Long scriptResult = redisTemplate.execute(
                tokenBucketScript,
                List.of(redisKey),
                String.valueOf(currentTimeMs),           // ARGV[1] — now
                String.valueOf(rule.getMaxRequests()),   // ARGV[2] — bucket capacity
                String.valueOf(refillRatePerMs),         // ARGV[3] — tokens per ms
                String.valueOf(ttlSeconds)               // ARGV[4] — key TTL
        );

        return buildResult(scriptResult, rule, resetAtMs, currentTimeMs);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Maps the raw Lua return value to a {@link RateLimitResult}.
     *
     * <p>Null-safe: a null result (Redis error / script flush) fails closed
     * to prevent accidental unlimited access during infrastructure failures.
     *
     * @param scriptResult  ≥ 0 = remaining tokens (allowed), -1 = empty (rejected), null = error
     * @param rule          the enforced rule (for capacity and key logging)
     * @param resetAtMs     epoch ms when the next token will be available
     * @param currentTimeMs time the check was performed
     */
    private RateLimitResult buildResult(Long scriptResult,
                                        RateLimitRule rule,
                                        long resetAtMs,
                                        long currentTimeMs) {
        if (scriptResult == null) {
            log.warn("Token bucket script returned null for key={}. Failing closed.", rule.redisKey());
            return RateLimitResult.rejected(rule.getMaxRequests(), resetAtMs, currentTimeMs);
        }

        if (scriptResult >= 0) {
            log.debug("ALLOWED (token bucket) — remainingTokens={} key={}", scriptResult, rule.redisKey());
            return RateLimitResult.allowed(
                    scriptResult.intValue(),
                    rule.getMaxRequests(),
                    resetAtMs
            );
        }

        log.debug("REJECTED (token bucket) — bucket empty key={}", rule.redisKey());
        return RateLimitResult.rejected(rule.getMaxRequests(), resetAtMs, currentTimeMs);
    }
}
