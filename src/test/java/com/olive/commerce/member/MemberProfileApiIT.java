package com.olive.commerce.member;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-012: 마이페이지 프로필 및 배송지 CRUD API 검증.
 *
 * AC.1: GET /api/me는 phone, grade를 포함해야 함
 * AC.2: PATCH /api/me로 name, phone 수정 가능
 * AC.3: 배송지 추가/목록/수정/삭제
 * AC.4: 소유권 검증 — 타 회원의 주소 접근 불가
 * AC.5: 기본 배송지 변경 시 기존 기본 배송지는 false로
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
    "management.endpoint.health.group.readiness.include=" // 기존 설정 비우기
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MemberProfileApiIT extends PostgresIntegrationSupport {

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
    @Autowired private MemberRepository members;
    @Autowired private MemberAddressRepository addresses;
    @Autowired private MemberGradeRepository grades;
    @PersistenceContext private EntityManager em;

    private String aliceToken;
    private String bobToken;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void setup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            // members만 정리; member_grades는 V2 시드 그대로 사용
            em.createNativeQuery(
                "TRUNCATE member_refresh_tokens, member_login_histories, member_addresses, members RESTART IDENTITY CASCADE"
            ).executeUpdate();
        });

        // V2 마이그레이션 시드의 BRONZE 등급 ID 조회
        long bronzeGradeId = grades.findAll().stream()
            .filter(g -> "BRONZE".equals(g.getName()))
            .findFirst()
            .orElseThrow()
            .getId();

        aliceId = signup("alice@example.com", "Pass123!", "Alice", "010-1111-2222", bronzeGradeId);
        bobId = signup("bob@example.com", "Pass123!", "Bob", "010-2222-3333", bronzeGradeId);

        aliceToken = login("alice@example.com", "Pass123!");
        bobToken = login("bob@example.com", "Pass123!");
    }

    // ---------------------------------------------------------------------
    // AC.1: GET /api/me returns phone and grade
    // ---------------------------------------------------------------------
    @Test
    void getProfile_returnsPhoneAndGrade() throws Exception {
        mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.memberId").value((int) aliceId))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"))
            .andExpect(jsonPath("$.data.name").value("Alice"))
            .andExpect(jsonPath("$.data.phone").value("010-1111-2222"))
            .andExpect(jsonPath("$.data.grade").value("BRONZE"))
            .andExpect(jsonPath("$.data.role").value("USER"));
    }

    // ---------------------------------------------------------------------
    // AC.2: PATCH /api/me updates name and phone
    // ---------------------------------------------------------------------
    @Test
    void patchProfile_updatesNameAndPhone() throws Exception {
        mockMvc.perform(patch("/api/me")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MemberDtos.UpdateProfileRequest(
                    "Alice Updated", "010-9999-8888"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Alice Updated"))
            .andExpect(jsonPath("$.data.phone").value("010-9999-8888"));

        // DB 반영 확인
        Member m = members.findById(aliceId).orElseThrow();
        assertThat(m.getName()).isEqualTo("Alice Updated");
        assertThat(m.getPhone()).isEqualTo("010-9999-8888");
    }

    @Test
    void patchProfile_validationFails_withoutName() throws Exception {
        mockMvc.perform(patch("/api/me")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"010-9999-8888\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void patchProfile_keepsExistingPhone_whenPhoneOmitted() throws Exception {
        mockMvc.perform(patch("/api/me")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice Renamed\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("Alice Renamed"))
            .andExpect(jsonPath("$.data.phone").value("010-1111-2222"));

        Member m = members.findById(aliceId).orElseThrow();
        assertThat(m.getName()).isEqualTo("Alice Renamed");
        assertThat(m.getPhone()).isEqualTo("010-1111-2222");
    }

    // ---------------------------------------------------------------------
    // AC.3: Address CRUD
    // ---------------------------------------------------------------------
    @Test
    void createAddress_returnsCreated() throws Exception {
        mockMvc.perform(post("/api/me/addresses")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MemberDtos.CreateAddressRequest(
                    "Home Alice", "010-1111-2222", "12345", "서울시 강남구", "101호", true))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.recipientName").value("Home Alice"))
            .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    @Test
    void listAddresses_defaultFirst() throws Exception {
        // alice: default + non-default
        createAddress(aliceToken, "Office Alice", true);
        createAddress(aliceToken, "Home Alice", false);

        mockMvc.perform(get("/api/me/addresses")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].isDefault").value(true))
            .andExpect(jsonPath("$.data[1].isDefault").value(false));
    }

    @Test
    void updateAddress_modifiesOwnAddress() throws Exception {
        long addressId = createAddress(aliceToken, "Old Name", false);

        mockMvc.perform(patch("/api/me/addresses/" + addressId)
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MemberDtos.UpdateAddressRequest(
                    "New Name", "010-5555-6666", "54321", "부산시 해운대구", "202호", false))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.recipientName").value("New Name"))
            .andExpect(jsonPath("$.data.phone").value("010-5555-6666"));
    }

    @Test
    void deleteAddress_removesOwnAddress() throws Exception {
        // 2개의 배송지 생성 (삭제 후에도 1개 남도록)
        createAddress(aliceToken, "Keep This", true);
        long toDeleteId = createAddress(aliceToken, "To Delete", false);

        mockMvc.perform(delete("/api/me/addresses/" + toDeleteId)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isNoContent());

        assertThat(addresses.findById(toDeleteId)).isEmpty();
    }

    @Test
    void deleteOnlyAddress_rejected() throws Exception {
        long addressId = createAddress(aliceToken, "Only Address", true);

        mockMvc.perform(delete("/api/me/addresses/" + addressId)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CANNOT_DELETE_ONLY_ADDRESS"));
    }

    // ---------------------------------------------------------------------
    // AC.4: Ownership validation — member A cannot access member B's address
    // ---------------------------------------------------------------------
    @Test
    void updateAddress_otherMembersAddress_returns403() throws Exception {
        long bobAddressId = createAddress(bobToken, "Bob's Address", false);

        mockMvc.perform(patch("/api/me/addresses/" + bobAddressId)
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MemberDtos.UpdateAddressRequest(
                    "Hacked", "010-0000-0000", "00000", "해킹", "무", false))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_OWNED"));
    }

    @Test
    void deleteAddress_otherMembersAddress_returns403() throws Exception {
        long bobAddressId = createAddress(bobToken, "Bob's Address", false);

        mockMvc.perform(delete("/api/me/addresses/" + bobAddressId)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_OWNED"));
    }

    // ---------------------------------------------------------------------
    // AC.5: Setting new default demotes previous default
    // ---------------------------------------------------------------------
    @Test
    void setDefaultAddress_demotesPreviousDefault() throws Exception {
        createAddress(aliceToken, "First Default", true);
        long secondId = createAddress(aliceToken, "Second Default", true);

        mockMvc.perform(get("/api/me/addresses")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].isDefault").value(true))
            .andExpect(jsonPath("$.data[0].id").value((int) secondId))
            .andExpect(jsonPath("$.data[1].isDefault").value(false));
    }

    // ---------------------------------------------------------------------
    // GET /api/me/summary
    // ---------------------------------------------------------------------
    @Test
    void getSummary_returnsZeroCountsForNewMember() throws Exception {
        mockMvc.perform(get("/api/me/summary")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.pointBalance").value(0))
            .andExpect(jsonPath("$.data.usableCouponCount").value(0))
            .andExpect(jsonPath("$.data.totalOrderCount").value(0))
            .andExpect(jsonPath("$.data.grade").value("BRONZE"));
    }

    @Test
    void getSummary_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me/summary"))
            .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------
    private long signup(String email, String password, String name, String phone, long gradeId) throws Exception {
        // signup은 auth controller를 통해 처리되므로 gradeId는 DB에 직접 설정
        MvcResult r = mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    new com.olive.commerce.auth.AuthDtos.SignupRequest(email, password, name, phone))))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode tree = json.readTree(r.getResponse().getContentAsString());
        long memberId = tree.path("data").path("memberId").asLong();

        // gradeId를 올바르게 설정
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE members SET grade_id = :gradeId WHERE id = :memberId")
                .setParameter("gradeId", gradeId)
                .setParameter("memberId", memberId)
                .executeUpdate();
        });

        return memberId;
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

    private long createAddress(String token, String recipientName, boolean isDefault) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/me/addresses")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MemberDtos.CreateAddressRequest(
                    recipientName, "010-1111-2222", "12345", "서울시", "상세", isDefault))))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode tree = json.readTree(r.getResponse().getContentAsString());
        return tree.path("data").path("id").asLong();
    }
}
