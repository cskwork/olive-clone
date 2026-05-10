package com.olive.commerce.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesUuid_whenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String responseId = res.getHeader("X-Request-Id");
        assertThat(responseId).isNotBlank();
        UUID.fromString(responseId);
        verify(chain).doFilter(req, res);
    }

    @Test
    void echoesIncomingRequestId_whenHeaderProvided() throws ServletException, IOException {
        String incoming = "11111111-1111-1111-1111-111111111111";
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        req.addHeader("X-Request-Id", incoming);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(res.getHeader("X-Request-Id")).isEqualTo(incoming);
    }

    @Test
    void clearsMdcAfterRequest_evenIfDownstreamThrows() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (request, response) -> {
            assertThat(MDC.get("traceId")).isNotBlank();
            throw new RuntimeException("downstream blew up");
        };

        try {
            filter.doFilter(req, res, chain);
        } catch (RuntimeException expected) {
            // 요청 처리 중 예외가 나도 MDC는 깨끗해야 한다.
        }

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void rejectsMalformedHeader_byGeneratingFresh() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/test");
        req.addHeader("X-Request-Id", "not-a-uuid; DROP TABLE members;--");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        String responseId = res.getHeader("X-Request-Id");
        UUID.fromString(responseId);
        assertThat(responseId).isNotEqualTo("not-a-uuid; DROP TABLE members;--");
    }
}
