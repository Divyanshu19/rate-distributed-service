package com.ratelimiter.distributed.api.exception;

import com.ratelimiter.distributed.admin.exception.AdminRuleNotFoundException;
import com.ratelimiter.distributed.admin.exception.RuleAlreadyExistsException;
import com.ratelimiter.distributed.api.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Global exception handler for the rate-limiter REST API.
 *
 * <p><b>Security contract:</b> no handler in this class ever leaks:
 * <ul>
 *   <li>Stack traces</li>
 *   <li>Exception class names or fully qualified types</li>
 *   <li>Internal state (Redis keys, rule internals, Spring class names)</li>
 * </ul>
 * Every error is mapped to a safe {@link ErrorResponse} with a plain-English
 * {@code message} field.
 *
 * <h2>Handled exceptions</h2>
 * <table>
 *   <tr><th>Exception</th><th>HTTP Status</th><th>Cause</th></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400</td>
 *       <td>{@code @Valid} failed on request body fields</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>400</td>
 *       <td>Malformed or missing JSON body</td></tr>
 *   <tr><td>{@link MethodArgumentTypeMismatchException}</td><td>400</td>
 *       <td>Path variable of wrong type</td></tr>
 *   <tr><td>{@link RuleNotFoundException}</td><td>404</td>
 *       <td>No rule for the given tenant + endpoint</td></tr>
 *   <tr><td>{@link TenantNotFoundException}</td><td>404</td>
 *       <td>Tenant has no rules registered</td></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400</td>
 *       <td>Programming contract violated (e.g. unknown algorithm)</td></tr>
 *   <tr><td>{@link Exception} (catch-all)</td><td>500</td>
 *       <td>Any unexpected error — logs full details server-side only</td></tr>
 * </table>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    /**
     * Handles {@code @Valid} constraint violations on request body fields.
     *
     * <p>Collects every failing field constraint into the {@code details} list
     * so callers get one response for all validation problems at once.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .sorted()   // deterministic order for reliable testing
                .toList();

        log.debug("Validation failed: {}", details);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Validation failed",
                        details));
    }

    /**
     * Handles malformed JSON bodies (missing body, syntax errors, wrong types in JSON).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException ex) {

        log.debug("Unreadable HTTP message: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Request body is missing or malformed"));
    }

    /**
     * Handles path variable type mismatches (e.g. a non-string path variable).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        log.debug("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Invalid value for parameter: " + ex.getName()));
    }

    /**
     * Handles violated programming contracts (e.g. unknown Algorithm value at runtime).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex) {

        log.warn("Illegal argument: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        ex.getMessage()));
    }

    // ── 404 Not Found ─────────────────────────────────────────────────────────

    /**
     * Handles requests for a tenant + endpoint combination that has no rule configured.
     */
    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(RuleNotFoundException ex) {
        log.info("Rule not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        HttpStatus.NOT_FOUND.value(),
                        HttpStatus.NOT_FOUND.getReasonPhrase(),
                        ex.getMessage()));
    }

    /**
     * Handles requests for a tenant that has no rules registered at all.
     */
    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTenantNotFound(TenantNotFoundException ex) {
        log.info("Tenant not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        HttpStatus.NOT_FOUND.value(),
                        HttpStatus.NOT_FOUND.getReasonPhrase(),
                        ex.getMessage()));
    }

    /**
     * Handles admin PUT/DELETE targeting a rule ID that does not exist in MySQL.
     */
    @ExceptionHandler(AdminRuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAdminRuleNotFound(AdminRuleNotFoundException ex) {
        log.info("Admin rule not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        HttpStatus.NOT_FOUND.value(),
                        HttpStatus.NOT_FOUND.getReasonPhrase(),
                        ex.getMessage()));
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    /**
     * Handles admin CREATE when a rule for (tenantId, endpoint) already exists.
     */
    @ExceptionHandler(RuleAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleRuleAlreadyExists(RuleAlreadyExistsException ex) {
        log.info("Rule already exists: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        HttpStatus.CONFLICT.value(),
                        HttpStatus.CONFLICT.getReasonPhrase(),
                        ex.getMessage()));
    }

    // ── 500 Internal Server Error (catch-all) ─────────────────────────────────

    /**
     * Catch-all for every unexpected exception.
     *
     * <p>Logs the full stack trace server-side (for incident investigation)
     * but returns only a generic message to the caller — no internal details leaked.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Full stack trace logged server-side; NEVER sent to the client
        log.error("Unexpected error processing request", ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                        "An unexpected error occurred. Please try again later."));
    }
}
