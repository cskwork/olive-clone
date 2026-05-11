package com.olive.commerce.auth;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-011 AC end-to-end 검증 — 진짜 Postgres + Redis 위에서 4 엔드포인트 동작.
 *
 * - signup → login → /api/me happy path
 * - login 실패 시 member_login_histories 에 row 작성
 * - refresh 회전: 동일 토큰 두 번째 사용 시 401
 * - 5회 연속 실패 → 계정 잠금 → ACCOUNT_LOCKED
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIT extends PostgresIntegrationSupport {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void cleanState() {
        // 각 테스트 사이에 회원·토큰·로그인 이력을 비워 deterministic 시작점 확보.
        new TransactionTemplate(txManager).executeWithoutResult(s ->
            em.createNativeQuery(
                "TRUNCATE member_refresh_tokens, member_login_histories, member_addresses, members RESTART IDENTITY CASCADE"
            ).executeUpdate()
        );
        // Redis 의 잠금/실패 카운트도 모두 클리어 — 다른 테스트의 락이 다음 테스트에 새지 않게.
        var keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---------------------------------------------------------------------
    // AC.1: signup -> login -> /api/me echoes memberId
    // ---------------------------------------------------------------------
    @Test
    void signupThenLoginThenMe_returnsMemberId() throws Exception {
        long memberId = signup("alice@example.com", "Pa$$word123!", "Alice", "010-1111-2222");
        assertThat(memberId).isPositive();

        String access = login("alice@example.com", "Pa$$word123!");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value((int) memberId))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"))
            .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void signupDuplicateEmail_returns409() throws Exception {
        signup("dup@example.com", "Pa$$word123!", "Dup", "010-1111-3333");
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthDtos.SignupRequest(
                    "dup@example.com", "Pa$$word123!", "Dup2", "010-1111-3334"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_USED"));
    }

    @Test
    void signupValidationFails_returns400_withFieldErrors() throws Exception {
        // password 너무 짧음 (7자), email 형식 위반.
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"name\":\"X\",\"phone\":\"010-1111-2222\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.fieldErrors").isArray());
    }

    // ---------------------------------------------------------------------
    // AC.2: login failure writes history row with reason
    // ---------------------------------------------------------------------
    @Test
    void loginWrongPassword_returns401_andWritesHistory() throws Exception {
        signup("bob@example.com", "Pa$$word123!", "Bob", "010-2222-3333");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bob@example.com\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("BAD_CREDENTIALS"));

        Number failures = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM member_login_histories WHERE success = FALSE AND failure_reason = 'BAD_CREDENTIALS'"
        ).getSingleResult();
        assertThat(failures.intValue()).isEqualTo(1);
    }

    @Test
    void loginFiveTimesWrong_locksAccount_andSubsequentLoginReturnsLocked() throws Exception {
        signup("locked@example.com", "Pa$$word123!", "Locked", "010-2222-4444");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"locked@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        }
        // 5번째 실패는 LOCKED 로 전환되며 ACCOUNT_LOCKED 반환.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"locked@example.com\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isLocked())
            .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));

        // 락이 활성화된 상태에서 정확한 비밀번호도 LOCKED 로 거절돼야 한다.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"locked@example.com\",\"password\":\"Pa$$word123!\"}"))
            .andExpect(status().isLocked())
            .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));

        Number locked = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM member_login_histories WHERE failure_reason = 'ACCOUNT_LOCKED'"
        ).getSingleResult();
        assertThat(locked.intValue()).isGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // AC.3: refresh rotation rejects replay
    // ---------------------------------------------------------------------
    @Test
    void refreshRotation_replayedRefresh_returns401() throws Exception {
        signup("rot@example.com", "Pa$$word123!", "Rot", "010-3333-4444");
        JsonNode loginBody = loginFull("rot@example.com", "Pa$$word123!");
        String firstRefresh = loginBody.path("data").path("refreshToken").asText();

        // first rotation succeeds
        MvcResult ok = mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + firstRefresh + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").exists())
            .andReturn();
        String newRefresh = json.readTree(ok.getResponse().getContentAsString())
            .path("data").path("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(firstRefresh);

        // replay of the original refresh must fail
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + firstRefresh + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));

        // new refresh works once
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
            .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // AC.4: passwords stored as bcrypt cost ≥ 12, never returned
    // ---------------------------------------------------------------------
    @Test
    void passwordIsBcrypt12_andNeverEchoed() throws Exception {
        signup("crypt@example.com", "Pa$$word123!", "Crypto", "010-3333-5555");

        String hash = (String) em.createNativeQuery(
            "SELECT password_hash FROM members WHERE email = 'crypt@example.com'"
        ).getSingleResult();
        // bcrypt 식별자: $2a$12$... or $2b$12$...
        assertThat(hash).matches("^\\$2[ab]\\$12\\$.*");

        // signup/login response 어디에도 비밀번호가 echo 되지 않는지 확인
        MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"echo@example.com\",\"password\":\"Pa$$word123!\",\"name\":\"Echo\",\"phone\":\"010-4444-5555\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        assertThat(signupResult.getResponse().getContentAsString()).doesNotContain("Pa$$word123!");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"echo@example.com\",\"password\":\"Pa$$word123!\"}"))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(loginResult.getResponse().getContentAsString()).doesNotContain("Pa$$word123!");
    }

    // ---------------------------------------------------------------------
    // AC: logout 후 refresh 토큰이 모두 revoke 된다
    // ---------------------------------------------------------------------
    @Test
    void logout_revokesAllRefreshTokens_andSubsequentRefreshFails() throws Exception {
        signup("out@example.com", "Pa$$word123!", "Out", "010-4444-6666");
        JsonNode body = loginFull("out@example.com", "Pa$$word123!");
        String access = body.path("data").path("accessToken").asText();
        String refresh = body.path("data").path("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revokedTokens").isNumber());

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private long signup(String email, String password, String name, String phone) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new AuthDtos.SignupRequest(email, password, name, phone))))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode tree = json.readTree(r.getResponse().getContentAsString());
        return tree.path("data").path("memberId").asLong();
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode tree = json.readTree(r.getResponse().getContentAsString());
        return tree.path("data").path("accessToken").asText();
    }

    private JsonNode loginFull(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }
}
