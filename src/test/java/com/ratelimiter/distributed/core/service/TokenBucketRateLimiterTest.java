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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * <p>No Redis required — {@link RedisTemplate} is fully mocked.
 * Tests verify:
 * <ul>
 *   <li>Burst handling — first N requests all allowed immediately</li>
 *   <li>Rejection — once bucket is empty, requests are blocked</li>
 *   <li>Refill logic — correct refillRatePerMs passed to Lua</li>
 *   <li>Retry-After — correct ms-until-next-token on rejection</li>
 *   <li>Fail-closed — null Redis response → reject, not allow</li>
 *   <li>Algorithm routing — getSupportedAlgorithm() returns TOKEN_BUCKET</li>
 * </ul>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class TokenBucketRateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private DefaultRedisScript<Long> tokenBucketScript;

    private TokenBucketRateLimiter rateLimiter;

    // ── Fixed test data ──────────────────────────────────────────────────────

    private static final long CURRENT_TIME_MS = 1_700_000_000_000L;
    private static final int  MAX_REQUESTS    = 10;
    private static final int  WINDOW_SEC      = 60;

    private RateLimitRule defaultRule;

    @BeforeEach
    void setUp() {
        rateLimiter = new TokenBucketRateLimiter(redisTemplate, tokenBucketScript);

        defaultRule = RateLimitRule.builder()
                .tenantId("tenant-acme")
                .endpoint("/api/v1/search")
                .maxRequests(MAX_REQUESTS)
                .windowSizeSeconds(WINDOW_SEC)
                .algorithm(Algorithm.TOKEN_BUCKET)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Algorithm Identity
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getSupportedAlgorithm() must return TOKEN_BUCKET")
    void shouldSupportTokenBucketAlgorithm() {
        assertThat(rateLimiter.getSupportedAlgorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Burst Handling — bucket starts full
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Burst Handling")
    class BurstHandling {

        @Test
        @DisplayName("should ALLOW request and return 9 remaining when bucket is almost full")
        void shouldAllowRequest_withBucketAlmostFull() {
            // Lua returns 9 → consumed 1 token, 9 remaining
            givenScriptReturns(9L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemainingRequests()).isEqualTo(9);
            assertThat(result.getTotalAllowed()).isEqualTo(MAX_REQUESTS);
        }

        @Test
        @DisplayName("should ALLOW request when last token is consumed (0 remaining)")
        void shouldAllowRequest_whenLastTokenConsumed() {
            // Lua returns 0 → this consumed the final token
            givenScriptReturns(0L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.getRemainingRequests()).isZero();
        }

        @Test
        @DisplayName("should ALLOW burst of maxRequests immediately (Lua simulated sequence)")
        void shouldAllowBurst_upToCapacity() {
            // Simulate 10 sequential calls: Lua returns 9,8,7...0
            // Each returns true and decrements. All should be ALLOWED.
            for (long remaining = MAX_REQUESTS - 1; remaining >= 0; remaining--) {
                givenScriptReturns(remaining);
                RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);
                assertThat(result.isAllowed())
                        .as("Request with %d remaining should be allowed", remaining)
                        .isTrue();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rejection — empty bucket
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Empty Bucket Rejection")
    class EmptyBucketRejection {

        @Test
        @DisplayName("should REJECT when bucket is empty (Lua returns -1)")
        void shouldRejectRequest_whenBucketEmpty() {
            givenScriptReturns(-1L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getRemainingRequests()).isZero();
            assertThat(result.getTotalAllowed()).isEqualTo(MAX_REQUESTS);
        }

        @Test
        @DisplayName("should return retryAfterMs equal to time-to-refill-one-token")
        void shouldReturnCorrectRetryAfterMs_onRejection() {
            givenScriptReturns(-1L);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // refillRatePerMs = 10 / 60_000 = 0.0001667 tokens/ms
            // msPerToken = ceil(1 / 0.0001667) = ceil(6000.0) = 6000ms
            double refillRatePerMs = (double) MAX_REQUESTS / (WINDOW_SEC * 1000.0);
            long expectedMsPerToken = (long) Math.ceil(1.0 / refillRatePerMs);

            assertThat(result.getRetryAfterMs()).isEqualTo(expectedMsPerToken);
        }

        @Test
        @DisplayName("should REJECT and FAIL CLOSED when Redis returns null")
        void shouldFailClosed_whenRedisReturnsNull() {
            givenScriptReturns(null);

            RateLimitResult result = rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // Must fail closed — accidental allow-on-error is worse than transient reject
            assertThat(result.isAllowed()).isFalse();
            assertThat(result.getRemainingRequests()).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Refill Logic — Lua receives correct arguments
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Refill Rate Calculation")
    class RefillRateCalculation {

        @Test
        @DisplayName("should pass correct refillRatePerMs to Lua (10 req / 60s)")
        void shouldPassCorrectRefillRate_forDefaultRule() {
            givenScriptReturns(5L);
            ArgumentCaptor<String> argv3 = ArgumentCaptor.forClass(String.class);

            rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            // ARGV: [now, capacity, refillRatePerMs, ttl]
            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), anyList(),
                    any(), any(), argv3.capture(), any());

            // 10 requests / 60_000ms = 0.0001666... tokens/ms
            double expectedRate = (double) MAX_REQUESTS / (WINDOW_SEC * 1000.0);
            double actualRate   = Double.parseDouble(argv3.getValue());

            assertThat(actualRate).isCloseTo(expectedRate, within(1e-10));
        }

        @Test
        @DisplayName("should pass capacity (maxRequests) as ARGV[2]")
        void shouldPassCapacity_toScript() {
            givenScriptReturns(5L);
            ArgumentCaptor<String> argv2 = ArgumentCaptor.forClass(String.class); // capacity

            rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), anyList(),
                    any(), argv2.capture(), any(), any());

            assertThat(argv2.getValue()).isEqualTo(String.valueOf(MAX_REQUESTS));
        }

        @Test
        @DisplayName("should pass correct Redis key to Lua")
        @SuppressWarnings("unchecked")
        void shouldPassCorrectRedisKey() {
            givenScriptReturns(5L);
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);

            rateLimiter.isAllowed(defaultRule, CURRENT_TIME_MS);

            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), keysCaptor.capture(),
                    any(), any(), any(), any());

            assertThat(keysCaptor.getValue())
                    .hasSize(1)
                    .containsExactly("rate_limit:tenant-acme:/api/v1/search");
        }

        @Test
        @DisplayName("refillRate should scale correctly with different rule parameters")
        void shouldScaleRefillRate_withDifferentRules() {
            // Rule: 100 req / 10s → rate = 100 / 10_000 = 0.01 tokens/ms
            RateLimitRule fastRule = defaultRule.toBuilder()
                    .maxRequests(100)
                    .windowSizeSeconds(10)
                    .build();

            givenScriptReturns(50L);
            ArgumentCaptor<String> argv3 = ArgumentCaptor.forClass(String.class);

            rateLimiter.isAllowed(fastRule, CURRENT_TIME_MS);

            verify(redisTemplate).execute(
                    any(DefaultRedisScript.class), anyList(),
                    any(), any(), argv3.capture(), any());

            double actualRate = Double.parseDouble(argv3.getValue());
            assertThat(actualRate).isCloseTo(0.01, within(1e-10));
        }
    }

    // ── Test helper ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void givenScriptReturns(Long returnValue) {
        // doReturn avoids varargs type-matching issues with when().thenReturn()
        // The script receives 4 ARGV values: now, capacity, refillRatePerMs, ttl
        doReturn(returnValue)
                .when(redisTemplate)
                .execute(any(DefaultRedisScript.class), anyList(),
                         any(), any(), any(), any());
    }
}
