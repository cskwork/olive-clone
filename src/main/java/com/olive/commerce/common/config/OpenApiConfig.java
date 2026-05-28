package com.olive.commerce.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 문서 메타데이터.
 *
 * <p>Swagger UI: {@code /swagger-ui.html}, OpenAPI JSON: {@code /v3/api-docs}.
 * 스토어프론트 SPA가 타입 계약(contract)으로 사용한다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI commerceOpenAPI() {
        return new OpenAPI().info(new Info()
            .title("Olive Young Clone Commerce API")
            .description("자가 호스팅 가능한 헬스앤뷰티 커머스 백엔드 공개/회원 API")
            .version("0.1.0"));
    }
}
