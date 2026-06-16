package com.ratelimiter.distributed.admin.cache;

import com.ratelimiter.distributed.core.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache for {@link RateLimitRule} objects.
 *
 * <p>The Admin API writes rules to MySQL first, then invalidates (or refreshes)
 * this cache. The hot-path check endpoint reads from here to avoid a MySQL
 * round-trip on every request.
 *
 * <h2>Key format</h2>
 * <pre>
 *   admin:rule:{tenantId}:{endpoint}
 * </pre>
 * This namespace is separate from the rate-limiter counters
 * ({@code rate_limit:{tenantId}:{endpoint}}) to avoid any key collision.
 *
 * <h2>Cache TTL</h2>
 * <p>Each cached rule has a 5-minute TTL as a safety net. In practice,
 * explicit invalidation on every admin write ensures stale data is never
 * served — the TTL is just a backstop for unexpected scenarios.
 *
 * <h2>Value format</h2>
 * <p>Rules are stored as pipe-delimited strings:
 * <pre>
 *   {tenantId}|{endpoint}|{maxRequests}|{windowSizeSeconds}|{algorithm}
 * </pre>
 * Plain strings keep Redis keys human-readable in {@code redis-cli} and
 * avoid adding a JSON serialisation dependency to the hot path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleRedisCache {

    private static final String KEY_PREFIX  = "admin:rule:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String   SEPARATOR  = "|";

    private final RedisTemplate<String, String> redisTemplate;

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Stores a rule in the cache, overwriting any previous value.
     * Called after a successful CREATE or UPDATE in MySQL.
     *
     * @param rule the rule to cache
     */
    public void put(RateLimitRule rule) {
        String key   = cacheKey(rule.getTenantId(), rule.getEndpoint());
        String value = serialise(rule);
        redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        log.debug("Rule cached — key={}", key);
    }

    /**
     * Removes a rule from the cache.
     * Called after a successful DELETE in MySQL.
     *
     * <p>After deletion, the next hot-path lookup will result in a cache miss,
     * causing the registry to fall back to MySQL (where the rule no longer
     * exists) and correctly returning a 404.
     *
     * @param tenantId the tenant of the deleted rule
     * @param endpoint the endpoint of the deleted rule
     */
    public void evict(String tenantId, String endpoint) {
        String key = cacheKey(tenantId, endpoint);
        Boolean deleted = redisTemplate.delete(key);
        log.info("Rule evicted from cache — key={} deleted={}", key, deleted);
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /**
     * Looks up a rule from the cache.
     *
     * @param tenantId the tenant to look up
     * @param endpoint the endpoint to look up
     * @return the cached rule, or {@link Optional#empty()} on a cache miss
     */
    public Optional<RateLimitRule> get(String tenantId, String endpoint) {
        String key   = cacheKey(tenantId, endpoint);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            log.debug("Cache miss — key={}", key);
            return Optional.empty();
        }
        log.debug("Cache hit — key={}", key);
        return Optional.of(deserialise(value));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String cacheKey(String tenantId, String endpoint) {
        return KEY_PREFIX + tenantId + ":" + endpoint;
    }

    /**
     * Serialises a rule to a pipe-delimited string.
     * Field order: tenantId|endpoint|maxRequests|windowSizeSeconds|algorithm
     */
    private static String serialise(RateLimitRule rule) {
        return rule.getTenantId()    + SEPARATOR
             + rule.getEndpoint()    + SEPARATOR
             + rule.getMaxRequests() + SEPARATOR
             + rule.getWindowSizeSeconds() + SEPARATOR
             + rule.getAlgorithm().name();
    }

    /**
     * Deserialises a pipe-delimited string back to a {@link RateLimitRule}.
     * Mirror image of {@link #serialise(RateLimitRule)}.
     */
    private static RateLimitRule deserialise(String value) {
        String[] parts = value.split("\\" + SEPARATOR, 5);
        return RateLimitRule.builder()
                .tenantId(parts[0])
                .endpoint(parts[1])
                .maxRequests(Integer.parseInt(parts[2]))
                .windowSizeSeconds(Integer.parseInt(parts[3]))
                .algorithm(com.ratelimiter.distributed.core.model.Algorithm.valueOf(parts[4]))
                .build();
    }
}
