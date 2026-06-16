package com.ratelimiter.distributed.api.controller;

import com.ratelimiter.distributed.api.dto.RateLimitCheckRequest;
import com.ratelimiter.distributed.api.dto.RateLimitCheckResponse;
import com.ratelimiter.distributed.api.dto.TenantRulesResponse;
import com.ratelimiter.distributed.api.exception.RuleNotFoundException;
import com.ratelimiter.distributed.api.exception.TenantNotFoundException;
import com.ratelimiter.distributed.api.registry.RuleRegistry;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import com.ratelimiter.distributed.core.service.RateLimiterPort;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing the rate-limiting API.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/ratelimit/check}           — evaluate a single request</li>
 *   <li>{@code GET  /api/v1/ratelimit/rules/{tenantId}} — fetch rules for a tenant</li>
 * </ul>
 *
 * <h2>Response headers</h2>
 * <p>Every {@code /check} response (allowed <em>and</em> rejected) carries:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — total quota defined by the rule</li>
 *   <li>{@code X-RateLimit-Remaining} — requests left in the current window</li>
 *   <li>{@code X-RateLimit-Reset}     — Unix epoch <em>seconds</em> when window resets</li>
 *   <li>{@code Retry-After}           — seconds to wait; present only on 429 responses</li>
 * </ul>
 *
 * <h2>HTTP status codes</h2>
 * <ul>
 *   <li>{@code 200 OK}          — request is within the rate limit</li>
 *   <li>{@code 400 Bad Request} — missing/blank required fields</li>
 *   <li>{@code 404 Not Found}   — tenant or rule not configured</li>
 *   <li>{@code 429 Too Many Requests} — rate limit exceeded</li>
 *   <li>{@code 500 Internal Server Error} — unexpected error (no stack trace leaked)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ratelimit")
@RequiredArgsConstructor
public class RateLimitController {

    // Standard rate-limit header names
    static final String HEADER_LIMIT     = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET     = "X-RateLimit-Reset";
    static final String HEADER_RETRY     = "Retry-After";

    private final RateLimiterPort rateLimiterPort;
    private final RuleRegistry    ruleRegistry;

    // ── POST /api/v1/ratelimit/check ──────────────────────────────────────────

    /**
     * Evaluates whether the incoming request is within the configured rate limit.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validate the request body ({@code @Valid}).</li>
     *   <li>Look up the rule for {@code (tenantId, endpoint)}.</li>
     *   <li>Delegate to {@link RateLimiterPort#isAllowed(RateLimitRule, long)}.</li>
     *   <li>Set standard rate-limit headers on the response.</li>
     *   <li>Return {@code 200} if allowed or {@code 429} if rejected.</li>
     * </ol>
     *
     * @param request validated request DTO
     * @return {@code 200} with {@link RateLimitCheckResponse} if allowed;
     *         {@code 429} with the same body shape if rejected
     * @throws RuleNotFoundException if no rule is configured for the tenant + endpoint
     */
    @PostMapping("/check")
    public ResponseEntity<RateLimitCheckResponse> check(
            @Valid @RequestBody RateLimitCheckRequest request) {

        log.debug("Rate-limit check — tenantId={} endpoint={} requestId={}",
                request.getTenantId(), request.getEndpoint(), request.getRequestId());

        RateLimitRule rule = ruleRegistry
                .findRule(request.getTenantId(), request.getEndpoint())
                .orElseThrow(() -> new RuleNotFoundException(
                        request.getTenantId(), request.getEndpoint()));

        long now = System.currentTimeMillis();
        RateLimitResult result = rateLimiterPort.isAllowed(rule, now);

        RateLimitCheckResponse body = toResponse(result, now);
        HttpHeaders headers = buildRateLimitHeaders(result);

        if (result.isAllowed()) {
            log.debug("ALLOWED — tenantId={} endpoint={} remaining={} requestId={}",
                    request.getTenantId(), request.getEndpoint(),
                    result.getRemainingRequests(), request.getRequestId());
            return ResponseEntity.ok().headers(headers).body(body);
        }

        log.info("REJECTED (429) — tenantId={} endpoint={} retryAfterMs={} requestId={}",
                request.getTenantId(), request.getEndpoint(),
                result.getRetryAfterMs(), request.getRequestId());
        headers.add(HEADER_RETRY, String.valueOf(result.getRetryAfterMs() / 1000));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(body);
    }

    // ── GET /api/v1/ratelimit/rules/{tenantId} ────────────────────────────────

    /**
     * Returns all rate-limit rules configured for the given tenant.
     *
     * <p>Useful for operators to verify that a tenant's rules are loaded
     * correctly, and for upstream systems to pre-flight the configuration.
     *
     * @param tenantId the tenant whose rules should be returned
     * @return {@code 200} with {@link TenantRulesResponse}
     * @throws TenantNotFoundException if the tenant has no rules registered
     */
    @GetMapping("/rules/{tenantId}")
    public ResponseEntity<TenantRulesResponse> getRules(@PathVariable String tenantId) {

        log.debug("Fetching rules for tenantId={}", tenantId);

        List<RateLimitRule> rules = ruleRegistry.findAllByTenant(tenantId);
        if (rules.isEmpty()) {
            throw new TenantNotFoundException(tenantId);
        }

        List<TenantRulesResponse.RuleView> ruleViews = rules.stream()
                .map(r -> TenantRulesResponse.RuleView.builder()
                        .endpoint(r.getEndpoint())
                        .maxRequests(r.getMaxRequests())
                        .windowSizeSeconds(r.getWindowSizeSeconds())
                        .algorithm(r.getAlgorithm())
                        .build())
                .toList();

        TenantRulesResponse response = TenantRulesResponse.builder()
                .tenantId(tenantId)
                .ruleCount(ruleViews.size())
                .rules(ruleViews)
                .build();

        return ResponseEntity.ok(response);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds the {@link RateLimitCheckResponse} body from a {@link RateLimitResult}.
     *
     * @param result  the decision from the rate-limiter
     * @param nowMs   the time the check was performed
     * @return the populated response DTO
     */
    private RateLimitCheckResponse toResponse(RateLimitResult result, long nowMs) {
        long resetAfterMs = Math.max(0, result.getResetAtEpochMs() - nowMs);
        return RateLimitCheckResponse.builder()
                .allowed(result.isAllowed())
                .remainingRequests(result.getRemainingRequests())
                .resetAfterMs(resetAfterMs)
                .retryAfterMs(result.isAllowed() ? null : result.getRetryAfterMs())
                .build();
    }

    /**
     * Populates the three standard rate-limit headers.
     *
     * <p>{@code X-RateLimit-Reset} uses Unix epoch <em>seconds</em> (not ms)
     * to match the IETF draft standard and common client expectations.
     *
     * @param result the rate-limiter decision
     * @return headers ready to attach to the response
     */
    private HttpHeaders buildRateLimitHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HEADER_LIMIT,     String.valueOf(result.getTotalAllowed()));
        headers.add(HEADER_REMAINING, String.valueOf(result.getRemainingRequests()));
        headers.add(HEADER_RESET,     String.valueOf(result.getResetAtEpochMs() / 1000));
        return headers;
    }
}
