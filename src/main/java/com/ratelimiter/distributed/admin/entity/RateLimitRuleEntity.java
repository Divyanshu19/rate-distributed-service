package com.ratelimiter.distributed.admin.entity;

import com.ratelimiter.distributed.core.model.Algorithm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity that persists a rate-limit rule in MySQL.
 *
 * <p>This is the durable source of truth. Redis caches these records for
 * fast hot-path reads; the Admin API writes here first, then invalidates cache.
 *
 * <p>The composite ({@code tenantId}, {@code endpoint}) is unique — enforced
 * both by the {@code @UniqueConstraint} here and by the DB-level constraint
 * in V1 migration.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "rate_limit_rules",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_tenant_endpoint",
        columnNames = {"tenant_id", "endpoint"}
    )
)
public class RateLimitRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identifies the calling tenant, e.g. {@code tesco-orders}. */
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    /** Normalised endpoint path, e.g. {@code /api/orders}. */
    @Column(name = "endpoint", nullable = false, length = 255)
    private String endpoint;

    /** Maximum requests permitted within the window. */
    @Column(name = "max_requests", nullable = false)
    private int maxRequests;

    /** Duration of the observation window in seconds. */
    @Column(name = "window_size_seconds", nullable = false)
    private int windowSizeSeconds;

    /** Algorithm applied to this rule. Stored as its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false, length = 30)
    private Algorithm algorithm;

    /** Set on INSERT, never updated after that. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Updated on every MERGE/UPDATE via {@link PreUpdate}. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPrePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
