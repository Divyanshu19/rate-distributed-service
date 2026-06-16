package com.ratelimiter.distributed.api.exception;

/**
 * Thrown when a requested tenant has no rules configured in the registry.
 *
 * <p>Mapped to {@code HTTP 404 Not Found} by the global exception handler.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(String tenantId) {
        super("No rules configured for tenant: " + tenantId);
    }
}
