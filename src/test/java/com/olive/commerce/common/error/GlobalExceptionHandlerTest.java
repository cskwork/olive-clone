package com.olive.commerce.common.error;

import com.olive.commerce.common.web.RequestIdFilter;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class,
    GlobalExceptionHandlerTest.TestController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void businessException_mapsTo404_andReturnsErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test/member-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("id=42"))
            .andExpect(jsonPath("$.error.path").value("/test/member-not-found"))
            .andExpect(jsonPath("$.error.traceId")
                .value(matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void businessException_paymentMismatch_mapsTo422() throws Exception {
        mockMvc.perform(get("/test/payment-mismatch"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));
    }

    @Test
    void validationFailure_mapsTo400_withFieldErrors() throws Exception {
        mockMvc.perform(post("/test/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.message").value("요청 본문 검증에 실패했습니다."))
            .andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));
    }

    @Test
    void uncaughtException_mapsTo500_andHidesStackTrace() throws Exception {
        mockMvc.perform(get("/test/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."))
            .andExpect(jsonPath("$.error.trace").doesNotExist())
            .andExpect(jsonPath("$.error.stackTrace").doesNotExist());
    }

    @Test
    void requestIdHeader_isEchoedBack_whenProvided() throws Exception {
        mockMvc.perform(get("/test/member-not-found")
                .header("X-Request-Id", "11111111-1111-1111-1111-111111111111"))
            .andExpect(header().string("X-Request-Id", "11111111-1111-1111-1111-111111111111"))
            .andExpect(jsonPath("$.error.traceId").value("11111111-1111-1111-1111-111111111111"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @org.springframework.web.bind.annotation.GetMapping("/member-not-found")
        void memberNotFound() {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "id=42");
        }

        @org.springframework.web.bind.annotation.GetMapping("/payment-mismatch")
        void paymentMismatch() {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "주문=ORD-1, 결제=10000, 청구=12000");
        }

        @PostMapping("/echo")
        EchoResponse echo(@Valid @RequestBody EchoRequest req) {
            return new EchoResponse(req.name());
        }

        @org.springframework.web.bind.annotation.GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("내부 라이브러리 오류 (this should NOT leak)");
        }
    }

    record EchoRequest(@NotBlank String name) {}
    record EchoResponse(String name) {}
}
