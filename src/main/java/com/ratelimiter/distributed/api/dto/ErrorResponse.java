package com.ratelimiter.distributed.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Standard error envelope returned by the global exception handler
 * for every non-2xx response.
 *
 * <p>Stack traces, exception class names, and internal details are <em>never</em>
 * included. The response is machine-readable (clients can switch on {@code status})
 * and human-readable ({@code message} is always plain English).
 *
 * <p>Example — 400 Bad Request:
 * <pre>{@code
 * {
 *   "status":    400,
 *   "error":     "Bad Request",
 *   "message":   "Validation failed",
 *   "timestamp": "2024-11-14T12:00:00Z",
 *   "details":   ["tenantId must not be blank", "endpoint must not be blank"]
 * }
 * }</pre>
 *
 * <p>Example — 404 Not Found (no details):
 * <pre>{@code
 * {
 *   "status":    404,
 *   "error":     "Not Found",
 *   "message":   "No rules configured for tenant: unknown-tenant",
 *   "timestamp": "2024-11-14T12:00:00Z"
 * }
 * }</pre>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    /** HTTP status code (mirrors the response status). */
    @JsonProperty("status")
    int status;

    /** Short HTTP reason phrase, e.g. {@code "Bad Request"}, {@code "Not Found"}. */
    @JsonProperty("error")
    String error;

    /** Human-readable explanation safe to expose to API consumers. */
    @JsonProperty("message")
    String message;

    /** ISO-8601 timestamp of when the error occurred. */
    @JsonProperty("timestamp")
    Instant timestamp;

    /**
     * Optional list of field-level validation messages.
     * Present only for 400 validation errors; {@code null} otherwise.
     */
    @JsonProperty("details")
    List<String> details;

    // ── Static factory helpers ─────────────────────────────────────────────

    /**
     * Creates a simple error response without field-level details.
     *
     * @param status  HTTP status code
     * @param error   HTTP reason phrase
     * @param message safe, human-readable explanation
     * @return a fully populated {@link ErrorResponse}
     */
    public static ErrorResponse of(int status, String error, String message) {
        return ErrorResponse.builder()
                .status(status)
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Creates an error response with individual field-level validation messages.
     *
     * @param status  HTTP status code
     * @param error   HTTP reason phrase
     * @param message high-level message (e.g. "Validation failed")
     * @param details one entry per failing field constraint
     * @return a fully populated {@link ErrorResponse} with details
     */
    public static ErrorResponse ofValidation(int status, String error,
                                             String message, List<String> details) {
        return ErrorResponse.builder()
                .status(status)
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .details(details)
                .build();
    }
}
