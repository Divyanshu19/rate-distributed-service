package com.ratelimiter.distributed.api.exception;

/**
 * Thrown when no rate-limit rule is found for a given tenant + endpoint pair.
 *
 * <p>Mapped to {@code HTTP 404 Not Found} by the global exception handler.
 */
public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(String tenantId, String endpoint) {
        super("No rule configured for tenant '" + tenantId + "' on endpoint '" + endpoint + "'");
    }
}
