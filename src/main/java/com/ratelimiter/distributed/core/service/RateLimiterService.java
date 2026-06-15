package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Primary implementation of {@link RateLimiterPort}.
 *
 * <p>This service does <b>not</b> implement any algorithm itself.
 * Its single responsibility is <b>routing</b>: given a rule, find the correct
 * {@link RateLimitStrategy} and delegate the decision to it.
 *
 * <h2>How routing works</h2>
 * <p>Spring injects every {@code @Service} that implements {@link RateLimitStrategy}
 * as a {@code List}. On startup ({@link PostConstruct}), we build an
 * {@link EnumMap} keyed by {@link Algorithm}. On every request, one map
 * lookup selects the correct algorithm — O(1), no if-else chains.
 *
 * <pre>
 *   Startup:
 *     strategies = [SlidingWindowRateLimiter, TokenBucketRateLimiter]
 *     strategyMap = {
 *       SLIDING_WINDOW → SlidingWindowRateLimiter,
 *       TOKEN_BUCKET   → TokenBucketRateLimiter
 *     }
 *
 *   Per request:
 *     rule.getAlgorithm() == TOKEN_BUCKET
 *     → strategyMap.get(TOKEN_BUCKET)
 *     → TokenBucketRateLimiter.isAllowed(rule, now)
 * </pre>
 *
 * <h2>Adding a new algorithm</h2>
 * <p>Create a new {@code @Service} class implementing {@link RateLimitStrategy}
 * and add the corresponding value to the {@link Algorithm} enum.
 * Spring auto-discovers it. This class requires zero changes.
 */
@Slf4j
@Service
public class RateLimiterService implements RateLimiterPort {

    private final List<RateLimitStrategy> strategies;
    private final Map<Algorithm, RateLimitStrategy> strategyMap;

    /**
     * @param strategies all {@link RateLimitStrategy} beans discovered by Spring —
     *                   currently {@code SlidingWindowRateLimiter} and
     *                   {@code TokenBucketRateLimiter}
     */
    public RateLimiterService(List<RateLimitStrategy> strategies) {
        this.strategies  = strategies;
        this.strategyMap = new EnumMap<>(Algorithm.class);
    }

    /**
     * Builds the strategy routing map at application startup.
     *
     * <p>Validates that every {@link Algorithm} enum value has a corresponding
     * strategy registered. Fails fast at startup rather than at request-time.
     *
     * @throws IllegalStateException if any Algorithm value has no strategy
     */
    @PostConstruct
    void buildStrategyMap() {
        for (RateLimitStrategy strategy : strategies) {
            Algorithm algo = strategy.getSupportedAlgorithm();
            strategyMap.put(algo, strategy);
            log.info("Registered rate-limit strategy: {} → {}",
                    algo, strategy.getClass().getSimpleName());
        }

        // Validate: every known Algorithm must have a registered strategy.
        for (Algorithm algo : Algorithm.values()) {
            if (!strategyMap.containsKey(algo)) {
                throw new IllegalStateException(
                        "No RateLimitStrategy registered for Algorithm." + algo
                        + ". Add a @Service implementing RateLimitStrategy.getSupportedAlgorithm() == " + algo);
            }
        }

        log.info("RateLimiterService ready — {} algorithm(s) registered.", strategyMap.size());
    }

    /**
     * Routes the request to the algorithm declared in the rule.
     *
     * @param rule          the rate-limit rule — its {@link RateLimitRule#getAlgorithm()}
     *                      field selects the strategy
     * @param currentTimeMs current epoch time in ms
     * @throws IllegalArgumentException if the rule's algorithm has no strategy
     *                                  (should never happen after {@link PostConstruct} validation)
     */
    @Override
    public RateLimitResult isAllowed(RateLimitRule rule, long currentTimeMs) {
        RateLimitStrategy strategy = strategyMap.get(rule.getAlgorithm());

        if (strategy == null) {
            // Defensive — PostConstruct should have caught this at startup.
            throw new IllegalArgumentException(
                    "No strategy found for algorithm: " + rule.getAlgorithm());
        }

        log.debug("Routing rule tenantId={} endpoint={} → {}",
                rule.getTenantId(), rule.getEndpoint(),
                strategy.getClass().getSimpleName());

        return strategy.isAllowed(rule, currentTimeMs);
    }
}
