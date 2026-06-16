-- ─────────────────────────────────────────────────────────────────────────────
-- V1 — Create rate_limit_rules table
--
-- This is the source-of-truth store for all tenant rate-limit rules.
-- Rules are loaded from here at startup and after any admin API mutation.
-- Redis acts only as a read-through cache; MySQL is the durable record.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS rate_limit_rules (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,

    -- Rule identity: the composite (tenant_id, endpoint) must be unique
    tenant_id           VARCHAR(100)    NOT NULL,
    endpoint            VARCHAR(255)    NOT NULL,

    -- Limit configuration
    max_requests        INT             NOT NULL,
    window_size_seconds INT             NOT NULL,
    algorithm           VARCHAR(30)     NOT NULL,   -- matches Algorithm enum name

    -- Audit timestamps (UTC, set by application)
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    -- Enforce uniqueness at DB level so the app can rely on it
    UNIQUE KEY uq_tenant_endpoint (tenant_id, endpoint),

    -- Fast lookup by tenant_id (used by GET /admin/rules and /api/v1/ratelimit/rules/{tenantId})
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Persistent store for per-tenant rate-limit rules. Redis caches these for hot reads.';
