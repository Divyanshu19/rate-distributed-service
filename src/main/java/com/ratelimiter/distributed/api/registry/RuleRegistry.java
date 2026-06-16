package com.ratelimiter.distributed.api.registry;

import com.ratelimiter.distributed.core.model.RateLimitRule;

import java.util.List;
import java.util.Optional;

/**
 * Port for the in-memory rule registry.
 *
 * <p>Provides lookup of {@link RateLimitRule} instances keyed by
 * {@code (tenantId, endpoint)} and bulk retrieval of all rules for a tenant.
 *
 * <p>The default implementation ({@link InMemoryRuleRegistry}) is seeded from
 * {@code application.yaml} via {@link RuleRegistryProperties}.  Future
 * implementations can back this with Redis or a database without touching
 * any controller code.
 */
public interface RuleRegistry {

    /**
     * Finds the rule for a specific tenant + endpoint combination.
     *
     * @param tenantId the tenant identifier
     * @param endpoint the normalised endpoint path, e.g. {@code /api/orders}
     * @return the matching rule, or {@link Optional#empty()} if none is configured
     */
    Optional<RateLimitRule> findRule(String tenantId, String endpoint);

    /**
     * Returns all rules configured for a given tenant.
     *
     * @param tenantId the tenant identifier
     * @return an unmodifiable list of rules; empty if the tenant is unknown
     */
    List<RateLimitRule> findAllByTenant(String tenantId);
}
