package com.ratelimiter.distributed.admin.service;

import com.ratelimiter.distributed.admin.cache.RuleRedisCache;
import com.ratelimiter.distributed.admin.dto.CreateRuleRequest;
import com.ratelimiter.distributed.admin.dto.RuleResponse;
import com.ratelimiter.distributed.admin.dto.UpdateRuleRequest;
import com.ratelimiter.distributed.admin.entity.RateLimitRuleEntity;
import com.ratelimiter.distributed.admin.exception.AdminRuleNotFoundException;
import com.ratelimiter.distributed.admin.exception.RuleAlreadyExistsException;
import com.ratelimiter.distributed.admin.repository.RateLimitRuleRepository;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for all Admin API rule operations.
 *
 * <h2>Write-through cache pattern</h2>
 * <p>Every mutation follows the same three-step sequence:
 * <ol>
 *   <li><b>Write to MySQL</b> — durable, transactional; source of truth.</li>
 *   <li><b>Update / evict Redis</b> — the hot-path check endpoint reads from
 *       here; updating immediately ensures no stale data is served.</li>
 *   <li><b>Return the response DTO</b> — caller gets the persisted state.</li>
 * </ol>
 *
 * <p>The Redis step is intentionally performed <em>after</em> the MySQL commit.
 * If Redis is unavailable, the write still succeeds — the next cache miss will
 * read from MySQL. If MySQL fails, the transaction rolls back and Redis is
 * never touched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRuleService {

    private final RateLimitRuleRepository repository;
    private final RuleRedisCache          cache;

    // ── CREATE ────────────────────────────────────────────────────────────────

    /**
     * Creates a new rate-limit rule.
     *
     * @param request validated request DTO
     * @return the persisted rule as a response DTO
     * @throws RuleAlreadyExistsException if a rule for (tenantId, endpoint) already exists
     */
    @Transactional
    public RuleResponse createRule(CreateRuleRequest request) {
        if (repository.existsByTenantIdAndEndpoint(request.getTenantId(), request.getEndpoint())) {
            throw new RuleAlreadyExistsException(request.getTenantId(), request.getEndpoint());
        }

        RateLimitRuleEntity entity = RateLimitRuleEntity.builder()
                .tenantId(request.getTenantId())
                .endpoint(request.getEndpoint())
                .maxRequests(request.getMaxRequests())
                .windowSizeSeconds(request.getWindowSizeSeconds())
                .algorithm(request.getAlgorithm())
                .build();

        entity = repository.save(entity);

        // Cache after successful DB write — fail open if Redis is down
        cacheQuietly(entity);

        log.info("Rule created — id={} tenantId={} endpoint={} maxRequests={} algorithm={}",
                entity.getId(), entity.getTenantId(), entity.getEndpoint(),
                entity.getMaxRequests(), entity.getAlgorithm());

        return toResponse(entity);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * Replaces the limits on an existing rule (PUT semantics — full replacement).
     *
     * <p>The (tenantId, endpoint) identity is immutable — only the limit fields
     * ({@code maxRequests}, {@code windowSizeSeconds}, {@code algorithm}) change.
     *
     * @param id      database ID of the rule to update
     * @param request validated replacement values
     * @return the updated rule as a response DTO
     * @throws AdminRuleNotFoundException if no rule with {@code id} exists
     */
    @Transactional
    public RuleResponse updateRule(Long id, UpdateRuleRequest request) {
        RateLimitRuleEntity entity = repository.findById(id)
                .orElseThrow(() -> new AdminRuleNotFoundException(id));

        entity.setMaxRequests(request.getMaxRequests());
        entity.setWindowSizeSeconds(request.getWindowSizeSeconds());
        entity.setAlgorithm(request.getAlgorithm());

        entity = repository.save(entity);

        // Immediately overwrite the cached entry so the next hot-path read
        // sees the new limit — this is the core "no-restart" requirement.
        cacheQuietly(entity);

        log.info("Rule updated — id={} tenantId={} endpoint={} maxRequests={} windowSizeSeconds={} algorithm={}",
                entity.getId(), entity.getTenantId(), entity.getEndpoint(),
                entity.getMaxRequests(), entity.getWindowSizeSeconds(), entity.getAlgorithm());

        return toResponse(entity);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    /**
     * Deletes a rule by ID.
     *
     * <p>Evicts the Redis cache entry so future hot-path requests for this
     * (tenantId, endpoint) see a cache miss and fail with 404 (no rule found).
     *
     * @param id database ID of the rule to delete
     * @throws AdminRuleNotFoundException if no rule with {@code id} exists
     */
    @Transactional
    public void deleteRule(Long id) {
        RateLimitRuleEntity entity = repository.findById(id)
                .orElseThrow(() -> new AdminRuleNotFoundException(id));

        repository.delete(entity);
        evictQuietly(entity.getTenantId(), entity.getEndpoint());

        log.info("Rule deleted — id={} tenantId={} endpoint={}",
                entity.getId(), entity.getTenantId(), entity.getEndpoint());
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    /**
     * Returns all rules, optionally filtered by tenant.
     *
     * @param tenantId if non-null, returns only rules for that tenant;
     *                 if null, returns all rules across all tenants
     * @return list of matching rules as response DTOs
     */
    @Transactional(readOnly = true)
    public List<RuleResponse> listRules(String tenantId) {
        List<RateLimitRuleEntity> entities = (tenantId != null && !tenantId.isBlank())
                ? repository.findAllByTenantId(tenantId)
                : repository.findAll();

        return entities.stream()
                .map(this::toResponse)
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Maps an entity to the domain model so it can be stored in the cache.
     */
    private RateLimitRule toDomain(RateLimitRuleEntity entity) {
        return RateLimitRule.builder()
                .tenantId(entity.getTenantId())
                .endpoint(entity.getEndpoint())
                .maxRequests(entity.getMaxRequests())
                .windowSizeSeconds(entity.getWindowSizeSeconds())
                .algorithm(entity.getAlgorithm())
                .build();
    }

    /**
     * Maps an entity to the API response DTO.
     */
    private RuleResponse toResponse(RateLimitRuleEntity entity) {
        return RuleResponse.builder()
                .id(entity.getId())
                .tenantId(entity.getTenantId())
                .endpoint(entity.getEndpoint())
                .maxRequests(entity.getMaxRequests())
                .windowSizeSeconds(entity.getWindowSizeSeconds())
                .algorithm(entity.getAlgorithm())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * Caches the entity after a successful DB write.
     * Logs a warning but does NOT throw if Redis is unavailable — the system
     * can always fall back to MySQL on a cache miss.
     */
    private void cacheQuietly(RateLimitRuleEntity entity) {
        try {
            cache.put(toDomain(entity));
        } catch (Exception ex) {
            log.warn("Redis cache write failed for tenantId={} endpoint={} — " +
                     "hot path will fall back to MySQL on next request. cause={}",
                     entity.getTenantId(), entity.getEndpoint(), ex.getMessage());
        }
    }

    /**
     * Evicts the entity from cache after a successful DB delete.
     * Same fault-tolerance rationale as {@link #cacheQuietly}.
     */
    private void evictQuietly(String tenantId, String endpoint) {
        try {
            cache.evict(tenantId, endpoint);
        } catch (Exception ex) {
            log.warn("Redis cache eviction failed for tenantId={} endpoint={} — " +
                     "stale entry will expire after TTL. cause={}",
                     tenantId, endpoint, ex.getMessage());
        }
    }
}
