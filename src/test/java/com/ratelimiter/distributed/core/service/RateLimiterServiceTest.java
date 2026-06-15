package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RateLimiterService} — the strategy router.
 *
 * <p>These tests verify the routing logic only. The algorithm implementations
 * are mocked — their correctness is tested in dedicated test classes.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock private RateLimitStrategy slidingWindowStrategy;
    @Mock private RateLimitStrategy tokenBucketStrategy;

    private RateLimiterService rateLimiterService;

    private static final long NOW = 1_700_000_000_000L;

    @BeforeEach
    void setUp() {
        // Configure each mock to report which algorithm it handles
        when(slidingWindowStrategy.getSupportedAlgorithm()).thenReturn(Algorithm.SLIDING_WINDOW);
        when(tokenBucketStrategy.getSupportedAlgorithm()).thenReturn(Algorithm.TOKEN_BUCKET);

        rateLimiterService = new RateLimiterService(
                List.of(slidingWindowStrategy, tokenBucketStrategy));

        // Trigger @PostConstruct manually (Spring doesn't run it in unit tests)
        rateLimiterService.buildStrategyMap();
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should route SLIDING_WINDOW rule to SlidingWindowRateLimiter")
    void shouldRouteToSlidingWindow_whenAlgorithmIsSlidingWindow() {
        RateLimitRule rule = buildRule(Algorithm.SLIDING_WINDOW);
        RateLimitResult expected = RateLimitResult.allowed(5, 10, NOW + 60_000);

        when(slidingWindowStrategy.isAllowed(rule, NOW)).thenReturn(expected);

        RateLimitResult result = rateLimiterService.isAllowed(rule, NOW);

        assertThat(result).isEqualTo(expected);
        verify(slidingWindowStrategy).isAllowed(rule, NOW);
        // Verify the OTHER strategy was never asked to make a decision
        verify(tokenBucketStrategy, never()).isAllowed(any(), anyLong());
    }

    @Test
    @DisplayName("should route TOKEN_BUCKET rule to TokenBucketRateLimiter")
    void shouldRouteToTokenBucket_whenAlgorithmIsTokenBucket() {
        RateLimitRule rule = buildRule(Algorithm.TOKEN_BUCKET);
        RateLimitResult expected = RateLimitResult.allowed(9, 10, NOW + 6_000);

        when(tokenBucketStrategy.isAllowed(rule, NOW)).thenReturn(expected);

        RateLimitResult result = rateLimiterService.isAllowed(rule, NOW);

        assertThat(result).isEqualTo(expected);
        verify(tokenBucketStrategy).isAllowed(rule, NOW);
        // Verify the OTHER strategy was never asked to make a decision
        verify(slidingWindowStrategy, never()).isAllowed(any(), anyLong());
    }

    @Test
    @DisplayName("should propagate the exact result returned by the strategy")
    void shouldPropagateStrategyResult_unchanged() {
        RateLimitRule rule    = buildRule(Algorithm.SLIDING_WINDOW);
        RateLimitResult rejected = RateLimitResult.rejected(10, NOW + 60_000, NOW);

        when(slidingWindowStrategy.isAllowed(rule, NOW)).thenReturn(rejected);

        RateLimitResult result = rateLimiterService.isAllowed(rule, NOW);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemainingRequests()).isZero();
        assertThat(result.getRetryAfterMs()).isEqualTo(60_000L);
    }

    // ── Startup Validation ───────────────────────────────────────────────────

    @Test
    @DisplayName("buildStrategyMap should throw if any Algorithm has no registered strategy")
    void shouldFailAtStartup_whenAlgorithmHasNoStrategy() {
        // Register only one strategy — SLIDING_WINDOW has no handler
        when(tokenBucketStrategy.getSupportedAlgorithm()).thenReturn(Algorithm.TOKEN_BUCKET);

        RateLimiterService incompleteService =
                new RateLimiterService(List.of(tokenBucketStrategy));

        // @PostConstruct should fail fast — better to crash at startup than at request-time
        assertThatThrownBy(incompleteService::buildStrategyMap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLIDING_WINDOW");
    }

    @Test
    @DisplayName("isAllowed should throw IllegalArgumentException for unknown algorithm at runtime")
    void shouldThrowAtRuntime_whenNoStrategyForAlgorithm() {
        // Force a null into the map by using a rule whose algorithm doesn't match any strategy
        RateLimitRule rule = buildRule(Algorithm.SLIDING_WINDOW);

        // Build a service with no strategies at all (bypasses PostConstruct guard for this test)
        RateLimiterService emptyService = new RateLimiterService(List.of());
        // Don't call buildStrategyMap — strategyMap is empty

        assertThatThrownBy(() -> emptyService.isAllowed(rule, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SLIDING_WINDOW");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private RateLimitRule buildRule(Algorithm algorithm) {
        return RateLimitRule.builder()
                .tenantId("tenant-test")
                .endpoint("/api/v1/test")
                .maxRequests(10)
                .windowSizeSeconds(60)
                .algorithm(algorithm)
                .build();
    }
}
