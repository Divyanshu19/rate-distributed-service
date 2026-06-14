package com.ratelimiter.distributed.core.service;

import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SlidingWindowRateLimiter}.
 *
 * <p>No Redis instance required — {@link RedisTemplate} is mocked via Mockito.
 * The tests verify:
 * <ul>
 *   <li>Correct allow/reject decisions based on Lua script return values</li>
 *   <li>Correct arguments forwarded to the Lua script</li>
 *   <li>Correct HTTP-header values in the result (remaining, reset time)</li>
 *   <li>Window boundary edge cases</li>
 *   <li>Defensive null-safe handling when Redis returns null</li>
 * </ul>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class SlidingWindowRateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private DefaultRedisScript<Long> slidingWindowScript;

    private SlidingWindowRateLimiter rateLimiter;

    // ── Fixed test data ──────────────────────────────────────────────────────

    private static final long CURRENT_TIME_MS   = 1_700_000_000_000L; // fixed epoch
    private static final int  WINDOW_SIZE_SEC   = 60;
    private static final int  MAX_REQUESTS      = 10;

    private RateLimitRule defaultRule;

    @BeforeEach
    void setUp() {
        rateLimiter = new SlidingWindowRateLimiter(redisTemplate, slidingWindowScript);

        defaultRule = RateLimitRule.builder()
                .tenantId("tenant-acme")
                .endpoint("/api/v1/orders")
                .maxRequests(MAX_REQUESTS)
                .windowSizeSeconds(WINDOW_SIZE_SEC)
                .algorithm(Algorithm.SLIDING_WINDOW)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Happy Path — Allowed Requests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Allowed Requests")
    class AllowedRequests {

        @Test
        @DisplayName("should ALLOW request when well under the limit (5 remaining)")
        void shouldAllowRequest_whenWellUnderLimit() {
            // Lua script returns 5 → 5 slots remain after this request
            givenLuaScriptReturns(5L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemainingRequests()).isEqualTo(5);
            assertThat(result.getTotalAllowed()).isEqualTo(MAX_REQUESTS);
        }

        @Test
        @DisplayName("should ALLOW request when using the very last slot (0 remaining)")
        void shouldAllowRequest_whenAtLastSlot() {
            // Lua script returns 0 → this was the final permitted request
            givenLuaScriptReturns(0L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemainingRequests()).isZero();
        }

        @Test
        @DisplayName("should ALLOW first-ever request for a key (9 remaining)")
        void shouldAllowRequest_firstEverRequest() {
            // Fresh key: 0 existing entries, so maxRequests - 0 - 1 = 9 remaining
            givenLuaScriptReturns((long) MAX_REQUESTS - 1);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemainingRequests()).isEqualTo(MAX_REQUESTS - 1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rejection — Window Full
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Rejected Requests")
    class RejectedRequests {

        @Test
        @DisplayName("should REJECT request when window is full (Lua returns -1)")
        void shouldRejectRequest_whenWindowFull() {
            // Lua script returns -1 → cap exceeded
            givenLuaScriptReturns(-1L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getRemainingRequests()).isZero();
            assertThat(result.getTotalAllowed()).isEqualTo(MAX_REQUESTS);
        }

        @Test
        @DisplayName("should REJECT and return correct retryAfterMs")
        void shouldRejectRequest_withCorrectRetryAfter() {
            givenLuaScriptReturns(-1L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            long expectedResetAtMs  = CURRENT_TIME_MS + (WINDOW_SIZE_SEC * 1000L);
            long expectedRetryAfter = expectedResetAtMs - CURRENT_TIME_MS;

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getResetAtEpochMs()).isEqualTo(expectedResetAtMs);
            assertThat(result.getRetryAfterMs()).isEqualTo(expectedRetryAfter);
        }

        @Test
        @DisplayName("should REJECT and fail closed when Redis returns null (defensive path)")
        void shouldRejectRequest_whenRedisReturnsNull() {
            // Simulates a Redis communication error / script flush
            givenLuaScriptReturns(null);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // Must fail closed — never allow through on Redis error
            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getRemainingRequests()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Lua Script Argument Verification
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Lua Script Arguments")
    class LuaScriptArguments {

        @Test
        @DisplayName("should pass correct Redis key derived from rule")
        @SuppressWarnings("unchecked")
        void shouldPassCorrectRedisKey() {
            givenLuaScriptReturns(5L);
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);

            rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // Match all 5 vararg slots individually: currentTimeMs, windowStartMs, maxRequests, ttl, member
            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), keysCaptor.capture(),
                    any(), any(), any(), any(), any());

            List<String> capturedKeys = keysCaptor.getValue();
            assertThat(capturedKeys).hasSize(1);
            assertThat(capturedKeys.get(0)).isEqualTo("rate_limit:tenant-acme:/api/v1/orders");
        }

        @Test
        @DisplayName("should pass correct windowStartMs as ARGV[2]")
        void shouldPassCorrectWindowStartMs() {
            givenLuaScriptReturns(5L);
            ArgumentCaptor<String> argv1 = ArgumentCaptor.forClass(String.class); // currentTimeMs
            ArgumentCaptor<String> argv2 = ArgumentCaptor.forClass(String.class); // windowStartMs
            ArgumentCaptor<String> argv3 = ArgumentCaptor.forClass(String.class); // maxRequests

            rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // 5 individual vararg slots: ARGV[1..5]
            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), anyList(),
                    argv1.capture(), argv2.capture(), argv3.capture(), any(), any());

            long expectedWindowStart = CURRENT_TIME_MS - (WINDOW_SIZE_SEC * 1000L);
            assertThat(argv1.getValue()).isEqualTo(String.valueOf(CURRENT_TIME_MS));
            assertThat(argv2.getValue()).isEqualTo(String.valueOf(expectedWindowStart));
            assertThat(argv3.getValue()).isEqualTo(String.valueOf(MAX_REQUESTS));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Window Boundary Edge Cases
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Window Boundary Edge Cases")
    class WindowBoundaryEdgeCases {

        @Test
        @DisplayName("should compute windowStartMs as exactly currentTime minus windowSizeMs")
        void shouldComputeExactWindowBoundary() {
            givenLuaScriptReturns(5L);
            ArgumentCaptor<String> argv2 = ArgumentCaptor.forClass(String.class); // windowStartMs

            long exactTime = 60_000L; // t=60s
            RateLimitRule rule = defaultRule.toBuilder()
                    .windowSizeSeconds(60)
                    .build();

            rateLimiter.isAllowed(rule, exactTime);

            // 5 individual vararg slots; we only assert on argv2 (windowStartMs)
            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), anyList(),
                    any(), argv2.capture(), any(), any(), any());

            // windowStart = 60_000 - 60_000 = 0
            // ZREMRANGEBYSCORE removes score ≤ 0 → entry at t=0ms is evicted (exclusive lower bound)
            assertThat(argv2.getValue()).isEqualTo("0");
        }

        @Test
        @DisplayName("should set resetAtEpochMs to currentTime + full window size")
        void shouldSetCorrectResetTime() {
            givenLuaScriptReturns(3L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            long expectedReset = CURRENT_TIME_MS + (WINDOW_SIZE_SEC * 1000L);
            assertThat(result.getResetAtEpochMs()).isEqualTo(expectedReset);
        }
    }

    // ── Test helper ──────────────────────────────────────────────────────────

    /**
     * Stubs the Lua script execution to return a fixed value.
     * Matches any key list and any varargs to keep tests focused
     * on the behaviour under test, not on Mockito wiring.
     */
    @SuppressWarnings("unchecked")
    private void givenLuaScriptReturns(Long returnValue) {
        // doReturn().when() is the correct pattern for varargs stubbing.
        // when().thenReturn() triggers argument matching eagerly and fails
        // when the actual call passes N individual varargs instead of Object[].
        doReturn(returnValue)
                .when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any());
    }
}
