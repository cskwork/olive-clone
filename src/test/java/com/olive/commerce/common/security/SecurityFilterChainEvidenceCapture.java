package com.olive.commerce.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.member.MemberRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA 단계 증거 수집용. AC 4 건 + hierarchy 의 실제 HTTP 응답 본문을
 * docs/OLV-005/qa/ac-evidence.txt 로 단일 파일에 떨어뜨린다.
 * 본 테스트는 항상 GREEN — assert 가 없고 응답 본문만 캡처한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.repositories.bootstrap-mode=default"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityFilterChainEvidenceCapture {

    private static final Path OUT = Paths.get("docs/OLV-005/qa/ac-evidence.txt");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void freshFile() throws Exception {
        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, "# OLV-005 — AC 실측 응답 (MockMvc)\n",
            StandardCharsets.UTF_8);
    }

    @Test
    @Order(1)
    void ac1_actuatorHealth_no_auth() throws Exception {
        MvcResult r = mockMvc.perform(get("/actuator/health")).andReturn();
        append("AC-1: GET /actuator/health (no auth)", r);
    }

    @Test
    @Order(2)
    void ac2_apiCart_no_auth_returns401() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/cart")).andReturn();
        append("AC-2: GET /api/cart (no auth)", r);
    }

    @Test
    @Order(3)
    void ac3_apiCart_user_returns200() throws Exception {
        String token = jwtTokenProvider.issueAccess(42L, MemberRole.USER);
        MvcResult r = mockMvc.perform(get("/api/cart")
            .header("Authorization", "Bearer " + token)).andReturn();
        append("AC-3: GET /api/cart (USER)", r);
    }

    @Test
    @Order(4)
    void ac4a_apiAdmin_user_returns403() throws Exception {
        String token = jwtTokenProvider.issueAccess(42L, MemberRole.USER);
        MvcResult r = mockMvc.perform(post("/api/admin/products")
            .contentType(MediaType.APPLICATION_JSON).content("{}")
            .header("Authorization", "Bearer " + token)).andReturn();
        append("AC-4a: POST /api/admin/products (USER) → 403", r);
    }

    @Test
    @Order(5)
    void ac4b_apiAdmin_productAdmin_returns201() throws Exception {
        String token = jwtTokenProvider.issueAccess(7L, MemberRole.PRODUCT_ADMIN);
        MvcResult r = mockMvc.perform(post("/api/admin/products")
            .contentType(MediaType.APPLICATION_JSON).content("{}")
            .header("Authorization", "Bearer " + token)).andReturn();
        append("AC-4b: POST /api/admin/products (PRODUCT_ADMIN) → 201", r);
    }

    @Test
    @Order(6)
    void hierarchy_superAdmin_returns201() throws Exception {
        String token = jwtTokenProvider.issueAccess(1L, MemberRole.SUPER_ADMIN);
        MvcResult r = mockMvc.perform(post("/api/admin/products")
            .contentType(MediaType.APPLICATION_JSON).content("{}")
            .header("Authorization", "Bearer " + token)).andReturn();
        append("Hierarchy: POST /api/admin/products (SUPER_ADMIN) → 201", r);
    }

    @AfterAll
    static void announce() {
        System.out.println("[OLV-005 QA] evidence written: " + OUT.toAbsolutePath());
    }

    private void append(String label, MvcResult r) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## ").append(label).append('\n');
        sb.append("HTTP/1.1 ").append(r.getResponse().getStatus()).append('\n');
        if (r.getResponse().getContentType() != null) {
            sb.append("Content-Type: ").append(r.getResponse().getContentType()).append('\n');
        }
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (body != null && !body.isEmpty()) {
            sb.append(prettyJsonOrRaw(body)).append('\n');
        } else {
            sb.append("(empty body)\n");
        }
        Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);
    }

    private String prettyJsonOrRaw(String raw) {
        try {
            Object json = objectMapper.readValue(raw, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            return raw;
        }
    }
}
