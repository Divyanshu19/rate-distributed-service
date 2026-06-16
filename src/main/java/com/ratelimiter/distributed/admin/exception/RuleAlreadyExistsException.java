package com.ratelimiter.distributed.admin.exception;

/**
 * Thrown when a {@code POST /admin/rules} request tries to create a rule
 * for a (tenantId, endpoint) pair that already exists.
 *
 * <p>Mapped to {@code HTTP 409 Conflict} by the global exception handler.
 */
public class RuleAlreadyExistsException extends RuntimeException {

    public RuleAlreadyExistsException(String tenantId, String endpoint) {
        super("A rule already exists for tenant '" + tenantId
              + "' on endpoint '" + endpoint + "'. Use PUT to update it.");
    }
}
