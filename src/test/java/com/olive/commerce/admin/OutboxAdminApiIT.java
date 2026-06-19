package com.olive.commerce.admin;

import com.olive.commerce.common.metrics.CommerceMetrics;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.search.OutboxEvent;
import com.olive.commerce.search.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for OutboxAdminController DLQ endpoints.
 *
 * <p>Verifies that:
 * - DLQ listing returns only dlq=true events.
 * - Requeue by id resets a single DLQ event to PENDING.
 * - Requeue-all resets all DLQ events to PENDING.
 * - The Prometheus gauge reflects the current DLQ count after each operation.
 * - Non-admin tokens receive 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class OutboxAdminApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private CommerceMetrics commerceMetrics;

    private Jwt superAdminToken;
    private Jwt userToken;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();

        superAdminToken = Jwt.withTokenValue("dummy")
            .header("alg", "RS256")
            .claim("sub", "1")
            .claim("role", "SUPER_ADMIN")
            .claim("typ", "access")
            .build();

        userToken = Jwt.withTokenValue("dummy")
            .header("alg", "RS256")
            .claim("sub", "2")
            .claim("role", "USER")
            .claim("typ", "access")
            .build();
    }

    // --- helpers ---

    private OutboxEvent saveDlqEvent(String eventType) {
        OutboxEvent event = OutboxEvent.create("PRODUCT", 1L, eventType, "{\"productId\":1}");
        // Simulate 5 failures to move it to DLQ
        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            event.markFailure("simulated error " + i);
        }
        return outboxEventRepository.save(event);
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor superAdminJwt() {
        return jwt().jwt(superAdminToken)
            .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    // --- tests ---

    @Test
    void listDlq_withSuperAdmin_returnsDlqEvents() throws Exception {
        saveDlqEvent("PRODUCT_INDEX_SYNC");
        saveDlqEvent("PRODUCT_INDEX_SYNC");

        mockMvc.perform(get("/api/admin/outbox/dlq").with(superAdminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.events").isArray())
            .andExpect(jsonPath("$.data.events[0].eventType").value("PRODUCT_INDEX_SYNC"))
            .andExpect(jsonPath("$.data.events[0].attemptCount").value(OutboxEvent.MAX_ATTEMPTS));
    }

    @Test
    void listDlq_updatesPrometheusGauge() throws Exception {
        saveDlqEvent("PRODUCT_INDEX_SYNC");
        saveDlqEvent("PRODUCT_INDEX_SYNC");

        mockMvc.perform(get("/api/admin/outbox/dlq").with(superAdminJwt()))
            .andExpect(status().isOk());

        assertThat(commerceMetrics.getOutboxDlqCount()).isEqualTo(2);
    }

    @Test
    void listDlq_withUserToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/outbox/dlq")
                .with(jwt().jwt(userToken).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void requeueById_resetsDlqEventToPending() throws Exception {
        OutboxEvent dlqEvent = saveDlqEvent("PRODUCT_INDEX_SYNC");
        assertThat(dlqEvent.isDlq()).isTrue();

        mockMvc.perform(post("/api/admin/outbox/dlq/" + dlqEvent.getId() + "/requeue")
                .with(superAdminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.requeued").value(1));

        OutboxEvent requeued = outboxEventRepository.findById(dlqEvent.getId()).orElseThrow();
        assertThat(requeued.isDlq()).isFalse();
        assertThat(requeued.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(requeued.getAttemptCount()).isZero();
    }

    @Test
    void requeueById_updatesGaugeAfterRequeue() throws Exception {
        OutboxEvent dlqEvent = saveDlqEvent("PRODUCT_INDEX_SYNC");

        mockMvc.perform(post("/api/admin/outbox/dlq/" + dlqEvent.getId() + "/requeue")
                .with(superAdminJwt()))
            .andExpect(status().isOk());

        assertThat(commerceMetrics.getOutboxDlqCount()).isZero();
    }

    @Test
    void requeueAll_resetsAllDlqEventsToPending() throws Exception {
        saveDlqEvent("PRODUCT_INDEX_SYNC");
        saveDlqEvent("PRODUCT_INDEX_SYNC");

        mockMvc.perform(post("/api/admin/outbox/dlq/requeue-all").with(superAdminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.requeued").value(2));

        List<OutboxEvent> all = outboxEventRepository.findAll();
        assertThat(all).allMatch(e -> e.getStatus() == OutboxEvent.OutboxStatus.PENDING);
        assertThat(all).allMatch(e -> !e.isDlq());
        assertThat(all).allMatch(e -> e.getAttemptCount() == 0);
        assertThat(commerceMetrics.getOutboxDlqCount()).isZero();
    }

    @Test
    void requeueById_nonExistentEvent_returnsZeroRequeued() throws Exception {
        mockMvc.perform(post("/api/admin/outbox/dlq/999999/requeue").with(superAdminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requeued").value(0));
    }
}
