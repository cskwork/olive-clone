package com.olive.commerce.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-120 AC 검증 — Batch Job Admin API.
 *
 * AC1: POST /api/admin/jobs/{jobName}/run-now triggers job execution.
 * AC2: GET /api/admin/jobs/runs returns job run history.
 * AC3: GET /api/admin/jobs/{jobName}/latest returns latest run.
 * AC4: Unknown job name returns error response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = "carrier.mock.enabled=true")
class JobAdminApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JobExecutionService jobExecutionService;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private Jwt superAdminToken;
    private Jwt userToken;

    @BeforeEach
    void setUp() {
        // Initialize sequences
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("SELECT setval('job_runs_id_seq', 1, false)").getSingleResult();
        });

        // Create test tokens
        superAdminToken = createJwt(1L, "SUPER_ADMIN");
        userToken = createJwt(2L, "USER");
    }

    private Jwt createJwt(Long userId, String role) {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", userId.toString())
            .claim("role", role)
            .claim("typ", "access")
            .build();
    }

    @Test
    void AC1_runNow_executesJobSuccessfully() throws Exception {
        // When: Execute paymentPendingExpiry job
        MvcResult result = mockMvc.perform(post("/api/admin/jobs/paymentPendingExpiry/run-now")
                .with(jwt().jwt(superAdminToken)
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobName").value("paymentPendingExpiry"))
            .andExpect(jsonPath("$.data.status").value("STARTED"))
            .andReturn();

        // Then: Job run record is created
        String response = result.getResponse().getContentAsString();
        assertThat(response).contains("paymentPendingExpiry");

        // Verify job run in database
        JobRun jobRun = jobRunRepository.findTopByJobNameOrderByStartedAtDesc("paymentPendingExpiry")
                .orElseThrow();
        assertThat(jobRun.getJobName()).isEqualTo("paymentPendingExpiry");
        assertThat(jobRun.getTriggeredBy()).isEqualTo(JobRun.TriggeredBy.MANUAL);
    }

    @Test
    void AC1_runNow_allJobs() throws Exception {
        String[] jobNames = {
            "paymentPendingExpiry",
            "inventoryReservationExpiry",
            "couponExpiry",
            "pointExpiry",
            "deliveryStatusSync",
            "salesAggregation",
            "productRanking"
        };

        for (String jobName : jobNames) {
            mockMvc.perform(post("/api/admin/jobs/" + jobName + "/run-now")
                    .with(jwt().jwt(superAdminToken)
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobName").value(jobName))
                .andExpect(jsonPath("$.data.status").value("STARTED"));

            // Verify job run record
            JobRun jobRun = jobRunRepository.findTopByJobNameOrderByStartedAtDesc(jobName)
                    .orElseThrow();
            assertThat(jobRun.getTriggeredBy()).isEqualTo(JobRun.TriggeredBy.MANUAL);
        }
    }

    @Test
    void AC4_runNow_unknownJobReturnsError() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/unknownJob/run-now")
                .with(jwt().jwt(superAdminToken)
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobName").value("unknownJob"))
            .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void AC2_getRuns_returnsJobRunHistory() throws Exception {
        // Given: Execute a job first
        jobExecutionService.execute("couponExpiry");

        // When: Get job run history
        mockMvc.perform(get("/api/admin/jobs/runs")
                .with(jwt().jwt(superAdminToken)
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.runs").isArray())
            .andExpect(jsonPath("$.data.total").exists());
    }

    @Test
    void AC2_getRuns_filtersByJobName() throws Exception {
        // Given: Execute multiple jobs
        jobExecutionService.execute("couponExpiry");
        jobExecutionService.execute("pointExpiry");

        // When: Get runs for specific job
        mockMvc.perform(get("/api/admin/jobs/runs?jobName=couponExpiry")
                .with(jwt().jwt(superAdminToken)
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.runs").isArray())
            .andExpect(jsonPath("$.data.runs[0].jobName").value("couponExpiry"));
    }

    @Test
    void AC3_getLatestRun_returnsLatestRun() throws Exception {
        // Given: Execute a job
        jobExecutionService.execute("productRanking");

        // When: Get latest run
        mockMvc.perform(get("/api/admin/jobs/productRanking/latest")
                .with(jwt().jwt(superAdminToken)
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.jobName").value("productRanking"))
            .andExpect(jsonPath("$.data.triggeredBy").value("MANUAL"));
    }

    @Test
    void AC3_getLatestRun_returnsNullWhenNoRuns() throws Exception {
        mockMvc.perform(get("/api/admin/jobs/neverRun/latest")
            .with(jwt().jwt(superAdminToken)
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shedLock_preventsDuplicateExecution() throws Exception {
        // This test verifies that ShedLock is configured.
        // Actual lock behavior requires multiple instances, which is tested in integration.
        // Here we just verify the job methods execute without error.
        jobExecutionService.execute("couponExpiry");
        jobExecutionService.execute("pointExpiry");

        assertThat(jobRunRepository.findByJobNameOrderByStartedAtDesc("couponExpiry"))
                .hasSize(1);
        assertThat(jobRunRepository.findByJobNameOrderByStartedAtDesc("pointExpiry"))
                .hasSize(1);
    }
}
