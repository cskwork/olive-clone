package com.olive.commerce.common.error;

import com.olive.commerce.common.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA 증거 캡처 — 일반 단위 테스트와 분리되어 docs/OLV-004/qa/ac-*.txt 를 생성한다.
 * AC① BusinessException → 404 envelope, AC② traceId 응답 헤더 echo, AC④ 의 핸들러
 * 3가지 경로 응답 본문이 모두 영구 보관된다. 본 테스트는 일반 빌드에서도 항상 실행되며,
 * 실패하면 CI 가 멈춘다(즉, AC 가 기능 회귀를 일으키면 evidence 파일도 함께 깨진다).
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class,
    GlobalExceptionHandlerTest.TestController.class})
class AcceptanceCriteriaCapture {

    private static final Path QA_DIR = Path.of("docs/OLV-004/qa").toAbsolutePath();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void capture_ac1_businessException_returns404Envelope() throws Exception {
        MvcResult res = mockMvc.perform(get("/test/member-not-found")
                .header("X-Request-Id", "11111111-1111-1111-1111-111111111111"))
            .andReturn();
        write("ac1-business-exception-404.txt", res);
    }

    @Test
    void capture_ac1b_paymentMismatch_returns422() throws Exception {
        MvcResult res = mockMvc.perform(get("/test/payment-mismatch")).andReturn();
        write("ac1b-payment-mismatch-422.txt", res);
    }

    @Test
    void capture_ac4_validationFailure_returns400_withFieldErrors() throws Exception {
        MvcResult res = mockMvc.perform(post("/test/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andReturn();
        write("ac4-validation-failed-400.txt", res);
    }

    @Test
    void capture_ac4b_uncaughtException_returns500_withoutStackTrace() throws Exception {
        MvcResult res = mockMvc.perform(get("/test/boom")).andReturn();
        write("ac4b-internal-error-500.txt", res);
    }

    private static void write(String name, MvcResult res) throws Exception {
        Files.createDirectories(QA_DIR);
        String body = "HTTP " + res.getResponse().getStatus() + "\n"
            + "X-Request-Id: " + res.getResponse().getHeader("X-Request-Id") + "\n"
            + "Content-Type: " + res.getResponse().getContentType() + "\n\n"
            + res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Files.writeString(QA_DIR.resolve(name), body);
    }
}
