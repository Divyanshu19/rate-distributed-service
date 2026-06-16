package com.ratelimiter.distributed.api.registry;

import com.ratelimiter.distributed.admin.cache.RuleRedisCache;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory implementation of {@link RuleRegistry}.
 *
 * <p>Rules are loaded once at startup from {@link RuleRegistryProperties}
 * (which binds the {@code rate-limiter.rules} section of {@code application.yaml}).
 * The registry is read-only at runtime — no concurrent-write protection needed.
 *
 * <h2>Two-level lookup (after Admin API integration)</h2>
 * <p>{@link #findRule} checks Redis first (populated by the Admin API on every
 * create/update). If the key is absent (cache miss), it falls back to the
 * in-memory map seeded from {@code application.yaml}. This means:
 * <ul>
 *   <li>Admin-API updates are visible on the very next request — no restart.</li>
 *   <li>YAML-seeded bootstrap rules work with zero DB/Redis activity at startup.</li>
 *   <li>Redis going down degrades gracefully to the last-known YAML config.</li>
 * </ul>
 *
 * <p>Two indexes are maintained for O(1) lookups:
 * <ul>
 *   <li>{@code ruleMap}   — {@code "tenantId:endpoint"} → rule (point lookup)</li>
 *   <li>{@code tenantMap} — {@code "tenantId"} → list of rules (tenant listing)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryRuleRegistry implements RuleRegistry {

    private final RuleRegistryProperties properties;

    /** Redis cache populated by the Admin API on every create/update. */
    private final RuleRedisCache ruleRedisCache;

    /** Point-lookup index: "tenantId:endpoint" → rule */
    private final Map<String, RateLimitRule> ruleMap = new HashMap<>();

    /** Tenant-listing index: tenantId → immutable list of rules */
    private final Map<String, List<RateLimitRule>> tenantMap = new HashMap<>();

    /**
     * Builds the two lookup indexes from the YAML-bound properties.
     * Runs once at startup; safe to call in tests as well.
     */
    @PostConstruct
    void buildIndexes() {
        for (RuleRegistryProperties.RuleDefinition def : properties.getRules()) {
            RateLimitRule rule = RateLimitRule.builder()
                    .tenantId(def.getTenantId())
                    .endpoint(def.getEndpoint())
                    .maxRequests(def.getMaxRequests())
                    .windowSizeSeconds(def.getWindowSizeSeconds())
                    .algorithm(def.getAlgorithm())
                    .build();

            String pointKey = def.getTenantId() + ":" + def.getEndpoint();
            ruleMap.put(pointKey, rule);

            tenantMap.computeIfAbsent(def.getTenantId(), k -> new java.util.ArrayList<>()).add(rule);

            log.info("Registered rule: tenantId={} endpoint={} maxRequests={} window={}s algorithm={}",
                    def.getTenantId(), def.getEndpoint(),
                    def.getMaxRequests(), def.getWindowSizeSeconds(), def.getAlgorithm());
        }

        // Seal tenant lists so callers cannot mutate internal state
        tenantMap.replaceAll((k, v) -> Collections.unmodifiableList(v));

        log.info("InMemoryRuleRegistry initialised — {} rule(s) across {} tenant(s) loaded.",
                ruleMap.size(), tenantMap.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Redis cache — populated by the Admin API; reflects any live updates.</li>
     *   <li>In-memory map — YAML-seeded bootstrap rules; used on cache miss
     *       (e.g. first request after startup before admin writes, or Redis down).</li>
     * </ol>
     */
    @Override
    public Optional<RateLimitRule> findRule(String tenantId, String endpoint) {
        // 1. Redis cache — picks up admin-API updates immediately
        Optional<RateLimitRule> cached = ruleRedisCache.get(tenantId, endpoint);
        if (cached.isPresent()) {
            return cached;
        }
        // 2. In-memory fallback — YAML-seeded bootstrap rules
        return Optional.ofNullable(ruleMap.get(tenantId + ":" + endpoint));
    }

    /** {@inheritDoc} */
    @Override
    public List<RateLimitRule> findAllByTenant(String tenantId) {
        return tenantMap.getOrDefault(tenantId, Collections.emptyList());
    }
}
