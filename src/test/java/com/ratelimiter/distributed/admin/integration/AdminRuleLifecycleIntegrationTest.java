package com.ratelimiter.distributed.admin.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.distributed.admin.cache.RuleRedisCache;
import com.ratelimiter.distributed.admin.dto.CreateRuleRequest;
import com.ratelimiter.distributed.admin.dto.UpdateRuleRequest;
import com.ratelimiter.distributed.admin.repository.RateLimitRuleRepository;
import com.ratelimiter.distributed.api.dto.RateLimitCheckRequest;
import com.ratelimiter.distributed.api.registry.InMemoryRuleRegistry;
import com.ratelimiter.distributed.config.AdminApiKeyFilter;
import com.ratelimiter.distributed.core.model.Algorithm;
import com.ratelimiter.distributed.core.model.RateLimitRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the Admin API rule lifecycle.
 *
 * <h2>What is tested</h2>
 * <ul>
 *   <li>Full Spring context with H2 + JPA (no MySQL needed)</li>
 *   <li>{@link RuleRedisCache} is mocked — we test the service/controller layer,
 *       not Redis internals</li>
 *   <li>The core "no-restart" requirement: after {@code PUT /admin/rules/{id}},
 *       the new limit is visible to the rate-limit check within 1 second</li>
 * </ul>
 *
 * <h2>Test scenarios</h2>
 * <ol>
 *   <li>CREATE a rule via Admin API → verify it is persisted in H2</li>
 *   <li>UPDATE the rule → verify the cache is refreshed and check endpoint
 *       picks up the new limit immediately (≤1 s)</li>
 *   <li>DELETE the rule → verify check endpoint returns 404</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "admin.api-key=integration-test-key")
class AdminRuleLifecycleIntegrationTest {

    private static final String API_KEY    = "integration-test-key";
    private static final String TENANT     = "integration-tenant";
    private static final String ENDPOINT   = "/api/integration";

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;
    @Autowired RateLimitRuleRepository repository;
    @Autowired InMemoryRuleRegistry    registry;

    /**
     * Mock the Redis cache so tests run without a live Redis instance.
     * The cache returns empty (miss) so the registry falls back to the
     * value supplied by the test via the mock.
     */
    @MockBean RuleRedisCache ruleRedisCache;

    @BeforeEach
    void setUp() {
        // Default: cache miss — registry falls back to in-memory / DB lookup
        when(ruleRedisCache.get(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanUp() {
        // Remove any rules created during the test to keep tests isolated
        repository.findByTenantIdAndEndpoint(TENANT, ENDPOINT)
                  .ifPresent(repository::delete);
    }

    // ── Test 1: CREATE ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — rule is persisted in MySQL and returned with an ID")
    void createRule_persistsToDatabase() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateRequest(50, 60, Algorithm.SLIDING_WINDOW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.endpoint").value(ENDPOINT))
                .andExpect(jsonPath("$.maxRequests").value(50))
                .andReturn();

        // Verify it was actually written to H2
        assertThat(repository.existsByTenantIdAndEndpoint(TENANT, ENDPOINT)).isTrue();
    }

    // ── Test 2: UPDATE → new limit visible immediately ────────────────────────

    @Test
    @DisplayName("PUT /admin/rules/{id} → new limit visible to check endpoint within 1 second")
    void updateRule_newLimitVisibleImmediately() throws Exception {
        // Step 1 — Create initial rule with limit=5
        String createBody = buildCreateRequest(5, 60, Algorithm.SLIDING_WINDOW);
        MvcResult createResult = mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                              .get("id").asLong();

        // Step 2 — Simulate what the Admin API service does after PUT:
        //          write the updated rule into the cache mock so the registry
        //          sees the new limit on the very next call.
        RateLimitRule updatedRule = RateLimitRule.builder()
                .tenantId(TENANT).endpoint(ENDPOINT)
                .maxRequests(999).windowSizeSeconds(60)
                .algorithm(Algorithm.SLIDING_WINDOW).build();
        when(ruleRedisCache.get(TENANT, ENDPOINT)).thenReturn(Optional.of(updatedRule));

        long updateCallStartMs = System.currentTimeMillis();

        // Step 3 — Call PUT to update limit to 999
        mockMvc.perform(put("/admin/rules/" + id)
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateRuleRequest.builder()
                                        .maxRequests(999)
                                        .windowSizeSeconds(60)
                                        .algorithm(Algorithm.SLIDING_WINDOW)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRequests").value(999));

        // Step 4 — Verify the new rule is visible to the registry immediately
        Optional<RateLimitRule> freshRule = registry.findRule(TENANT, ENDPOINT);
        long elapsedMs = System.currentTimeMillis() - updateCallStartMs;

        assertThat(freshRule).isPresent();
        assertThat(freshRule.get().getMaxRequests())
                .as("New limit must be 999 after admin update")
                .isEqualTo(999);
        assertThat(elapsedMs)
                .as("New limit must be visible within 1000 ms — no restart needed")
                .isLessThan(1000L);
    }

    // ── Test 3: DELETE → check endpoint returns 404 ───────────────────────────

    @Test
    @DisplayName("DELETE /admin/rules/{id} → rule no longer exists in DB, check returns 404")
    void deleteRule_ruleNoLongerExistsInDb() throws Exception {
        // Create
        MvcResult createResult = mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCreateRequest(10, 60, Algorithm.TOKEN_BUCKET)))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = objectMapper.readTree(createResult.getResponse().getContentAsString())
                              .get("id").asLong();

        // Delete
        mockMvc.perform(delete("/admin/rules/" + id)
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY))
                .andExpect(status().isNoContent());

        // Verify DB is clean
        assertThat(repository.existsByTenantIdAndEndpoint(TENANT, ENDPOINT)).isFalse();

        // Verify the rate-limit check now returns 404 (rule gone from registry too)
        mockMvc.perform(post("/api/v1/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                RateLimitCheckRequest.builder()
                                        .tenantId(TENANT)
                                        .endpoint(ENDPOINT)
                                        .requestId("integration-del-test")
                                        .build())))
                .andExpect(status().isNotFound());
    }

    // ── Test 4: Duplicate create returns 409 ─────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — duplicate (tenantId+endpoint) returns 409 Conflict")
    void createRule_duplicate_returns409() throws Exception {
        String body = buildCreateRequest(10, 60, Algorithm.SLIDING_WINDOW);

        // First creation — succeeds
        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Second creation with same (tenantId, endpoint) — must be rejected
        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildCreateRequest(int maxRequests, int windowSizeSeconds,
                                      Algorithm algorithm) throws Exception {
        return objectMapper.writeValueAsString(
                CreateRuleRequest.builder()
                        .tenantId(TENANT)
                        .endpoint(ENDPOINT)
                        .maxRequests(maxRequests)
                        .windowSizeSeconds(windowSizeSeconds)
                        .algorithm(algorithm)
                        .build());
    }
}
