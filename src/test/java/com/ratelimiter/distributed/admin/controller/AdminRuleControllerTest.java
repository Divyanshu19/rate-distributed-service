package com.ratelimiter.distributed.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.distributed.admin.dto.CreateRuleRequest;
import com.ratelimiter.distributed.admin.dto.RuleResponse;
import com.ratelimiter.distributed.admin.dto.UpdateRuleRequest;
import com.ratelimiter.distributed.admin.exception.AdminRuleNotFoundException;
import com.ratelimiter.distributed.admin.exception.RuleAlreadyExistsException;
import com.ratelimiter.distributed.admin.service.AdminRuleService;
import com.ratelimiter.distributed.api.exception.GlobalExceptionHandler;
import com.ratelimiter.distributed.config.AdminApiKeyFilter;
import com.ratelimiter.distributed.config.SecurityConfig;
import com.ratelimiter.distributed.core.model.Algorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice tests for {@link AdminRuleController}.
 *
 * <p>Spring Security (and our {@link AdminApiKeyFilter}) is loaded via
 * {@code @Import(SecurityConfig.class)} so API key authentication is
 * tested end-to-end. The {@link AdminRuleService} is replaced by a Mockito mock.
 */
@WebMvcTest(AdminRuleController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "admin.api-key=test-admin-key")
class AdminRuleControllerTest {

    private static final String VALID_KEY   = "test-admin-key";
    private static final String INVALID_KEY = "wrong-key";

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;

    @MockitoBean AdminRuleService adminRuleService;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final RuleResponse RULE_RESPONSE = RuleResponse.builder()
            .id(1L)
            .tenantId("tesco-orders")
            .endpoint("/api/orders")
            .maxRequests(100)
            .windowSizeSeconds(60)
            .algorithm(Algorithm.SLIDING_WINDOW)
            .createdAt(Instant.parse("2024-11-14T12:00:00Z"))
            .updatedAt(Instant.parse("2024-11-14T12:00:00Z"))
            .build();

    // ── POST /admin/rules — success ───────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — valid request with correct API key returns 201")
    void createRule_validKey_returns201() throws Exception {
        when(adminRuleService.createRule(any())).thenReturn(RULE_RESPONSE);

        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.tenantId").value("tesco-orders"))
                .andExpect(jsonPath("$.endpoint").value("/api/orders"))
                .andExpect(jsonPath("$.maxRequests").value(100))
                .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"));
    }

    // ── POST /admin/rules — duplicate ─────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — duplicate rule returns 409 Conflict")
    void createRule_duplicate_returns409() throws Exception {
        when(adminRuleService.createRule(any()))
                .thenThrow(new RuleAlreadyExistsException("tesco-orders", "/api/orders"));

        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("tesco-orders")));
    }

    // ── PUT /admin/rules/{id} — success ───────────────────────────────────────

    @Test
    @DisplayName("PUT /admin/rules/{id} — valid update returns 200 with updated rule")
    void updateRule_validKey_returns200() throws Exception {
        RuleResponse updated = RuleResponse.builder()
                .id(1L).tenantId("tesco-orders").endpoint("/api/orders")
                .maxRequests(200).windowSizeSeconds(30).algorithm(Algorithm.TOKEN_BUCKET)
                .createdAt(Instant.parse("2024-11-14T12:00:00Z"))
                .updatedAt(Instant.parse("2024-11-14T12:05:00Z"))
                .build();

        when(adminRuleService.updateRule(eq(1L), any())).thenReturn(updated);

        mockMvc.perform(put("/admin/rules/1")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateRuleRequest.builder()
                                        .maxRequests(200)
                                        .windowSizeSeconds(30)
                                        .algorithm(Algorithm.TOKEN_BUCKET)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRequests").value(200))
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"));
    }

    // ── PUT /admin/rules/{id} — not found ─────────────────────────────────────

    @Test
    @DisplayName("PUT /admin/rules/{id} — unknown id returns 404")
    void updateRule_unknownId_returns404() throws Exception {
        when(adminRuleService.updateRule(anyLong(), any()))
                .thenThrow(new AdminRuleNotFoundException(99L));

        mockMvc.perform(put("/admin/rules/99")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateRuleRequest.builder()
                                        .maxRequests(50).windowSizeSeconds(60)
                                        .algorithm(Algorithm.SLIDING_WINDOW).build())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(containsString("99")));
    }

    // ── DELETE /admin/rules/{id} — success ────────────────────────────────────

    @Test
    @DisplayName("DELETE /admin/rules/{id} — valid id returns 204 No Content")
    void deleteRule_validKey_returns204() throws Exception {
        doNothing().when(adminRuleService).deleteRule(1L);

        mockMvc.perform(delete("/admin/rules/1")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isNoContent());
    }

    // ── DELETE /admin/rules/{id} — not found ──────────────────────────────────

    @Test
    @DisplayName("DELETE /admin/rules/{id} — unknown id returns 404")
    void deleteRule_unknownId_returns404() throws Exception {
        doThrow(new AdminRuleNotFoundException(42L))
                .when(adminRuleService).deleteRule(42L);

        mockMvc.perform(delete("/admin/rules/42")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("42")));
    }

    // ── GET /admin/rules — success ────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/rules — returns list of all rules")
    void listRules_noFilter_returnsAll() throws Exception {
        when(adminRuleService.listRules(isNull())).thenReturn(List.of(RULE_RESPONSE));

        mockMvc.perform(get("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tenantId").value("tesco-orders"));
    }

    @Test
    @DisplayName("GET /admin/rules?tenantId=tesco-orders — filters by tenant")
    void listRules_withTenantFilter_returnsFiltered() throws Exception {
        when(adminRuleService.listRules("tesco-orders")).thenReturn(List.of(RULE_RESPONSE));

        mockMvc.perform(get("/admin/rules")
                        .param("tenantId", "tesco-orders")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value("tesco-orders"));
    }

    // ── Authentication tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — missing API key returns 401")
    void createRule_missingKey_returns401() throws Exception {
        mockMvc.perform(post("/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /admin/rules — wrong API key returns 401")
    void createRule_wrongKey_returns401() throws Exception {
        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, INVALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /admin/rules/{id} — wrong API key returns 401, not 404")
    void deleteRule_wrongKey_returns401() throws Exception {
        mockMvc.perform(delete("/admin/rules/1")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, INVALID_KEY))
                .andExpect(status().isUnauthorized());
    }

    // ── Validation tests ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/rules — missing tenantId returns 400")
    void createRule_missingTenantId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                CreateRuleRequest.builder()
                        .endpoint("/api/orders").maxRequests(50)
                        .windowSizeSeconds(60).algorithm(Algorithm.SLIDING_WINDOW)
                        .build());
        // Note: tenantId is missing — builder will set null, @NotBlank should fire

        mockMvc.perform(post("/admin/rules")
                        .header(AdminApiKeyFilter.API_KEY_HEADER, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"/api/orders\",\"maxRequests\":50,"
                                + "\"windowSizeSeconds\":60,\"algorithm\":\"SLIDING_WINDOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details[0]").value(containsString("tenantId")));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String createRequestJson() throws Exception {
        return objectMapper.writeValueAsString(
                CreateRuleRequest.builder()
                        .tenantId("tesco-orders")
                        .endpoint("/api/orders")
                        .maxRequests(100)
                        .windowSizeSeconds(60)
                        .algorithm(Algorithm.SLIDING_WINDOW)
                        .build());
    }
}
