package com.ratelimiter.distributed.admin.repository;

import com.ratelimiter.distributed.admin.entity.RateLimitRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RateLimitRuleEntity}.
 *
 * <p>Spring generates all SQL at startup — no hand-written queries needed
 * for the standard CRUD operations the Admin API requires.
 */
public interface RateLimitRuleRepository extends JpaRepository<RateLimitRuleEntity, Long> {

    /**
     * Finds the rule for a specific tenant + endpoint pair.
     * Used by the rule-check hot path to seed the Redis cache on a miss.
     */
    Optional<RateLimitRuleEntity> findByTenantIdAndEndpoint(String tenantId, String endpoint);

    /**
     * Returns all rules configured for a tenant.
     * Used by {@code GET /admin/rules?tenantId=…} and the tenant-listing endpoint.
     */
    List<RateLimitRuleEntity> findAllByTenantId(String tenantId);

    /**
     * Checks whether a rule already exists for a given (tenant, endpoint) pair.
     * Used by the CREATE endpoint to enforce the unique constraint at the service
     * layer before hitting the DB, giving a friendlier error message.
     */
    boolean existsByTenantIdAndEndpoint(String tenantId, String endpoint);
}
