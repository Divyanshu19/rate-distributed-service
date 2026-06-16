package com.ratelimiter.distributed.admin.exception;

/**
 * Thrown when a {@code PUT} or {@code DELETE} targets a rule ID that does not exist.
 *
 * <p>Mapped to {@code HTTP 404 Not Found} by the global exception handler.
 */
public class AdminRuleNotFoundException extends RuntimeException {

    public AdminRuleNotFoundException(Long id) {
        super("No rule found with id: " + id);
    }
}
