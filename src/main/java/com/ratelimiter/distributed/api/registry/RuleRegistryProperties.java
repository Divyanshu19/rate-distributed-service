package com.ratelimiter.distributed.api.registry;

import com.ratelimiter.distributed.core.model.Algorithm;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code rate-limiter.rules} section from {@code application.yaml}
 * into a typed list of rule definitions.
 *
 * <p>Example YAML:
 * <pre>{@code
 * rate-limiter:
 *   rules:
 *     - tenantId: tesco-orders
 *       endpoint: /api/orders
 *       maxRequests: 50
 *       windowSizeSeconds: 60
 *       algorithm: SLIDING_WINDOW
 *     - tenantId: tesco-orders
 *       endpoint: /api/search
 *       maxRequests: 200
 *       windowSizeSeconds: 60
 *       algorithm: TOKEN_BUCKET
 * }</pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RuleRegistryProperties {

    /** All configured rules; populated by Spring Boot config binding. */
    private List<RuleDefinition> rules = new ArrayList<>();

    /**
     * A single rule definition as declared in {@code application.yaml}.
     * Field names mirror {@link com.ratelimiter.distributed.core.model.RateLimitRule}.
     */
    @Getter
    @Setter
    public static class RuleDefinition {

        /** Tenant identifier — must match the value sent in the HTTP request. */
        private String tenantId;

        /** Normalised endpoint path, e.g. {@code /api/orders}. */
        private String endpoint;

        /** Maximum permitted requests within the window. */
        private int maxRequests;

        /** Observation window in seconds. */
        private int windowSizeSeconds;

        /**
         * Algorithm to apply. Defaults to {@link Algorithm#SLIDING_WINDOW}
         * when the YAML entry omits the field.
         */
        private Algorithm algorithm = Algorithm.SLIDING_WINDOW;
    }
}
