package com.ratelimiter.distributed.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ratelimiter.distributed.core.model.Algorithm;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Request body for {@code POST /admin/rules}.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "tenantId":          "tesco-orders",
 *   "endpoint":          "/api/orders",
 *   "maxRequests":       100,
 *   "windowSizeSeconds": 60,
 *   "algorithm":         "SLIDING_WINDOW"
 * }
 * }</pre>
 */
@Value
@Builder
@Jacksonized
public class CreateRuleRequest {

    @NotBlank(message = "tenantId must not be blank")
    @JsonProperty("tenantId")
    String tenantId;

    @NotBlank(message = "endpoint must not be blank")
    @JsonProperty("endpoint")
    String endpoint;

    @Min(value = 1, message = "maxRequests must be at least 1")
    @JsonProperty("maxRequests")
    int maxRequests;

    @Min(value = 1, message = "windowSizeSeconds must be at least 1")
    @JsonProperty("windowSizeSeconds")
    int windowSizeSeconds;

    /**
     * Algorithm to use. Defaults to {@code SLIDING_WINDOW} if omitted.
     * Must be a valid {@link Algorithm} enum name.
     */
    @NotNull(message = "algorithm must not be null")
    @JsonProperty("algorithm")
    Algorithm algorithm;
}
