package com.ratelimiter.distributed.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.distributed.api.dto.RateLimitCheckRequest;
import com.ratelimiter.distributed.api.exception.GlobalExceptionHandler;
import com.ratelimiter.distributed.api.exception.RuleNotFoundException;
import com.ratelimiter.distributed.api.exception.TenantNotFoundException;
import com.ratelimiter.distributed.api.registry.RuleRegistry;
import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitResult;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import com.ratelimiter.distributed.core.service.RateLimiterPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link RateLimitController} using {@code @WebMvcTest}.
 *
 * <p>Only the web layer is loaded — Redis, Lua scripts, and the real
 * {@link RateLimiterPort} implementation are replaced with Mockito mocks.
 * This keeps tests fast (no Spring context with Redis) and deterministic.
 */
@WebMvcTest(RateLimitController.class)
@Import(GlobalExceptionHandler.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RateLimiterPort rateLimiterPort;

    @MockitoBean
    private RuleRegistry ruleRegistry;

    private static final long NOW             = 1_700_000_000_000L;
    private static final long WINDOW_MS       = 60_000L;
    private static final long RESET_AT_EPOCH  = NOW + WINDOW_MS;

    private static final RateLimitRule TESCO_ORDERS_RULE = RateLimitRule.builder()
            .tenantId("tesco-orders")
            .endpoint("/api/orders")
            .maxRequests(50)
            .windowSizeSeconds(60)
            .algorithm(Algorithm.SLIDING_WINDOW)
            .build();

    // ── POST /check — allowed ─────────────────────────────────────────────────

    @Test
    @DisplayName("POST /check — allowed request returns 200 with correct body and headers")
    void checkAllowed_returns200WithBodyAndHeaders() throws Exception {
        RateLimitResult allowed = RateLimitResult.allowed(45, 50, RESET_AT_EPOCH);

        when(ruleRegistry.findRule("tesco-orders", "/api/orders"))
                .thenReturn(Optional.of(TESCO_ORDERS_RULE));
        when(rateLimiterPort.isAllowed(any(RateLimitRule.class), anyLong()))
                .thenReturn(allowed);

        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkRequestJson("tesco-orders", "/api/orders", "req-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remainingRequests").value(45))
                .andExpect(jsonPath("$.retryAfterMs").doesNotExist())
                // headers
                .andExpect(header().string(RateLimitController.HEADER_LIMIT,     "50"))
                .andExpect(header().string(RateLimitController.HEADER_REMAINING, "45"))
                .andExpect(header().exists(RateLimitController.HEADER_RESET));
    }

    // ── POST /check — rejected ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /check — rejected request returns 429 with retryAfterMs and Retry-After header")
    void checkRejected_returns429WithRetryInfo() throws Exception {
        RateLimitResult rejected = RateLimitResult.rejected(50, RESET_AT_EPOCH, NOW);

        when(ruleRegistry.findRule("tesco-orders", "/api/orders"))
                .thenReturn(Optional.of(TESCO_ORDERS_RULE));
        when(rateLimiterPort.isAllowed(any(RateLimitRule.class), anyLong()))
                .thenReturn(rejected);

        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkRequestJson("tesco-orders", "/api/orders", "req-002")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.remainingRequests").value(0))
                .andExpect(jsonPath("$.retryAfterMs").value(WINDOW_MS))
                // standard headers present on 429 too
                .andExpect(header().string(RateLimitController.HEADER_LIMIT,     "50"))
                .andExpect(header().string(RateLimitController.HEADER_REMAINING, "0"))
                .andExpect(header().exists(RateLimitController.HEADER_RESET))
                .andExpect(header().exists(RateLimitController.HEADER_RETRY));
    }

    // ── POST /check — validation failures ────────────────────────────────────

    @Test
    @DisplayName("POST /check — missing tenantId returns 400 with validation detail")
    void checkMissingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"/api/orders\",\"requestId\":\"req-003\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details", hasItem("tenantId must not be blank")));
    }

    @Test
    @DisplayName("POST /check — missing endpoint returns 400 with validation detail")
    void checkMissingEndpoint_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tesco-orders\",\"requestId\":\"req-004\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details", hasItem("endpoint must not be blank")));
    }

    @Test
    @DisplayName("POST /check — empty JSON body returns 400")
    void checkEmptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /check — no body returns 400 with safe message")
    void checkNoBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("malformed")));
    }

    // ── POST /check — rule not found ──────────────────────────────────────────

    @Test
    @DisplayName("POST /check — unknown tenant+endpoint returns 404")
    void checkUnknownRule_returns404() throws Exception {
        when(ruleRegistry.findRule("ghost-tenant", "/api/nowhere"))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkRequestJson("ghost-tenant", "/api/nowhere", "req-005")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("ghost-tenant")));
    }

    // ── GET /rules/{tenantId} — success ───────────────────────────────────────

    @Test
    @DisplayName("GET /rules/{tenantId} — known tenant returns 200 with rules list")
    void getRules_knownTenant_returns200() throws Exception {
        when(ruleRegistry.findAllByTenant("tesco-orders"))
                .thenReturn(List.of(TESCO_ORDERS_RULE));

        mockMvc.perform(get("/api/v1/ratelimit/rules/tesco-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tesco-orders"))
                .andExpect(jsonPath("$.ruleCount").value(1))
                .andExpect(jsonPath("$.rules[0].endpoint").value("/api/orders"))
                .andExpect(jsonPath("$.rules[0].maxRequests").value(50))
                .andExpect(jsonPath("$.rules[0].windowSizeSeconds").value(60))
                .andExpect(jsonPath("$.rules[0].algorithm").value("SLIDING_WINDOW"));
    }

    // ── GET /rules/{tenantId} — unknown tenant ────────────────────────────────

    @Test
    @DisplayName("GET /rules/{tenantId} — unknown tenant returns 404 with safe message")
    void getRules_unknownTenant_returns404() throws Exception {
        when(ruleRegistry.findAllByTenant("ghost-tenant"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ratelimit/rules/ghost-tenant"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("ghost-tenant")));
    }

    // ── No stack traces leaked ─────────────────────────────────────────────────

    @Test
    @DisplayName("Unexpected runtime exception returns 500 without stack trace in body")
    void unexpectedException_returns500WithNoStackTrace() throws Exception {
        when(ruleRegistry.findRule("tesco-orders", "/api/orders"))
                .thenReturn(Optional.of(TESCO_ORDERS_RULE));
        when(rateLimiterPort.isAllowed(any(RateLimitRule.class), anyLong()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkRequestJson("tesco-orders", "/api/orders", "req-500")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                // safe generic message — no internal details
                .andExpect(jsonPath("$.message").value(containsString("unexpected error")))
                // internal exception message must NOT appear in the response body
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(containsString("Redis connection refused"))));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String checkRequestJson(String tenantId, String endpoint, String requestId)
            throws Exception {
        RateLimitCheckRequest req = RateLimitCheckRequest.builder()
                .tenantId(tenantId)
                .endpoint(endpoint)
                .requestId(requestId)
                .build();
        return objectMapper.writeValueAsString(req);
    }
}
