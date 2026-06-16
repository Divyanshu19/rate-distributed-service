package com.ratelimiter.distributed.admin.controller;

import com.ratelimiter.distributed.admin.dto.CreateRuleRequest;
import com.ratelimiter.distributed.admin.dto.RuleResponse;
import com.ratelimiter.distributed.admin.dto.UpdateRuleRequest;
import com.ratelimiter.distributed.admin.service.AdminRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin REST controller for managing rate-limit rules at runtime.
 *
 * <p>All routes under {@code /admin/**} are protected by API key authentication
 * — see {@link com.ratelimiter.distributed.config.SecurityConfig}.
 * No unauthenticated request ever reaches this controller.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST   /admin/rules}          — create a new rule</li>
 *   <li>{@code PUT    /admin/rules/{id}}      — update an existing rule's limits</li>
 *   <li>{@code DELETE /admin/rules/{id}}      — remove a rule</li>
 *   <li>{@code GET    /admin/rules}           — list all rules (optional ?tenantId filter)</li>
 * </ul>
 *
 * <h2>HTTP status codes</h2>
 * <ul>
 *   <li>{@code 201 Created}          — rule created successfully</li>
 *   <li>{@code 200 OK}               — update or list succeeded</li>
 *   <li>{@code 204 No Content}       — delete succeeded</li>
 *   <li>{@code 400 Bad Request}      — validation failure</li>
 *   <li>{@code 401 Unauthorized}     — missing or wrong API key</li>
 *   <li>{@code 404 Not Found}        — rule ID does not exist</li>
 *   <li>{@code 409 Conflict}         — rule (tenantId+endpoint) already exists</li>
 *   <li>{@code 500 Internal Error}   — unexpected; no stack trace leaked</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/admin/rules")
@RequiredArgsConstructor
public class AdminRuleController {

    private final AdminRuleService adminRuleService;

    // ── POST /admin/rules ─────────────────────────────────────────────────────

    /**
     * Creates a new rate-limit rule.
     *
     * <p>Returns {@code 409 Conflict} if a rule for the same
     * (tenantId, endpoint) pair already exists.
     *
     * @param request validated rule definition
     * @return {@code 201 Created} with the full persisted rule
     */
    @PostMapping
    public ResponseEntity<RuleResponse> createRule(
            @Valid @RequestBody CreateRuleRequest request) {

        log.info("Admin: creating rule — tenantId={} endpoint={}",
                request.getTenantId(), request.getEndpoint());

        RuleResponse response = adminRuleService.createRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── PUT /admin/rules/{id} ─────────────────────────────────────────────────

    /**
     * Replaces the limits on an existing rule (full PUT replacement).
     *
     * <p>This is the key "no-restart" endpoint: after this call returns,
     * the new limit is effective immediately — the Redis cache is updated
     * as part of the same operation.
     *
     * @param id      the database ID of the rule to update
     * @param request validated replacement values
     * @return {@code 200 OK} with the updated rule
     */
    @PutMapping("/{id}")
    public ResponseEntity<RuleResponse> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRuleRequest request) {

        log.info("Admin: updating rule — id={} maxRequests={} windowSizeSeconds={} algorithm={}",
                id, request.getMaxRequests(), request.getWindowSizeSeconds(), request.getAlgorithm());

        RuleResponse response = adminRuleService.updateRule(id, request);
        return ResponseEntity.ok(response);
    }

    // ── DELETE /admin/rules/{id} ──────────────────────────────────────────────

    /**
     * Removes a rule by ID and evicts it from the Redis cache.
     *
     * <p>After this call, any rate-limit check for the deleted
     * (tenantId, endpoint) will receive a {@code 404 Not Found}.
     *
     * @param id the database ID of the rule to delete
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        log.info("Admin: deleting rule — id={}", id);
        adminRuleService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    // ── GET /admin/rules ──────────────────────────────────────────────────────

    /**
     * Lists all rules, optionally filtered by tenant.
     *
     * @param tenantId optional query param — if provided, only rules for
     *                 that tenant are returned
     * @return {@code 200 OK} with the (possibly empty) list of rules
     */
    @GetMapping
    public ResponseEntity<List<RuleResponse>> listRules(
            @RequestParam(required = false) String tenantId) {

        log.debug("Admin: listing rules — tenantId={}", tenantId);
        List<RuleResponse> rules = adminRuleService.listRules(tenantId);
        return ResponseEntity.ok(rules);
    }
}
