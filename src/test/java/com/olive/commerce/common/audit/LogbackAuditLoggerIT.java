package com.olive.commerce.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.auth.LoginAttemptGuard;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberLoginHistoryRepository;
import com.olive.commerce.member.MemberRefreshTokenRepository;
import com.olive.commerce.member.MemberRepository;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LogbackAuditLogger.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration")
@TestPropertySource(properties = {
    "olive.audit.dir=build/tmp/audit-it",
    "olive.security.jwt.access-ttl=PT30M",
    "olive.security.jwt.refresh-ttl=P14D"
})
class LogbackAuditLoggerIT {

    @Autowired
    AuditLogger auditLogger;

    // OLV-011: 신규 도메인 빈을 mock 으로 끊는다 — audit logger 만 검증.
    @MockBean MemberRepository memberRepository;
    @MockBean MemberRefreshTokenRepository memberRefreshTokenRepository;
    @MockBean MemberLoginHistoryRepository memberLoginHistoryRepository;
    @MockBean MemberGradeRepository memberGradeRepository;
    @MockBean LoginAttemptGuard loginAttemptGuard;

    @Test
    void writes_singleLineJson_to_dailyFile_withTraceIdFromMdc() throws IOException {
        MDC.put("traceId", "22222222-2222-2222-2222-222222222222");
        try {
            auditLogger.log("LOGIN_SUCCESS", Map.of("memberId", 42, "ip", "127.0.0.1"));
        } finally {
            MDC.clear();
        }

        // Resolve the file the audit appender is actually writing to. Spring Boot
        // initializes Logback once per JVM, so when another test context starts first
        // (test order differs between machines) olive.audit.dir from this class is not
        // applied and the file lives under the default log/ directory instead.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        RollingFileAppender<?> appender = (RollingFileAppender<?>)
            context.getLogger("olive.audit").getAppender("AUDIT_FILE");
        assertThat(appender).as("AUDIT_FILE appender").isNotNull();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path log = Paths.get(appender.getFile());
        assertThat(log.getFileName().toString()).isEqualTo("audit-" + today + ".log");
        assertThat(log).exists();

        List<String> lines = Files.readAllLines(log);
        assertThat(lines).isNotEmpty();

        // The file can be shared with other tests' audit lines (including other
        // LOGIN_SUCCESS events), so pick ours by the unique traceId set above.
        ObjectMapper mapper = new ObjectMapper();
        boolean found = false;
        for (String line : lines) {
            JsonNode node = mapper.readTree(line);
            if ("22222222-2222-2222-2222-222222222222".equals(node.path("traceId").asText())) {
                assertThat(node.path("event").asText()).isEqualTo("LOGIN_SUCCESS");
                assertThat(node.path("memberId").asInt()).isEqualTo(42);
                assertThat(node.path("ip").asText()).isEqualTo("127.0.0.1");
                assertThat(node.has("@timestamp")).isTrue();
                found = true;
                break;
            }
        }
        assertThat(found).as("LOGIN_SUCCESS 라인을 찾지 못함").isTrue();
    }
}
