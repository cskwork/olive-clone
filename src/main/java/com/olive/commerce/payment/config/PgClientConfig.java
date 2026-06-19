package com.olive.commerce.payment.config;

import com.olive.commerce.payment.client.PgClient;
import com.olive.commerce.payment.client.MockPgClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 클라이언트 설정.
 * olive.pg.provider 프로퍼티에 따라 실제 PG 또는 Mock PG를 주입한다.
 */
@Configuration
public class PgClientConfig {

    /**
     * Mock PG 클라이언트.
     * olive.pg.provider=mock일 때 활성화된다.
     *
     * <p>웹훅 서명 시크릿은 {@code olive.pg.webhook-secret} 프로퍼티에서 주입된다.
     * application.yml 에 개발용 기본값이 있고 application-test.yml 에 테스트용 값이 있다.
     */
    @Bean
    @ConditionalOnProperty(name = "olive.pg.provider", havingValue = "mock")
    public PgClient mockPgClient(
            @Value("${olive.pg.webhook-secret:mock-webhook-secret-for-testing}") String webhookSecret) {
        return new MockPgClient(webhookSecret);
    }

    // TODO OLV-080: 실제 PG(Toss) 연동 시 tossPgClient() 빈 추가
    // @Bean
    // @ConditionalOnProperty(name = "olive.pg.provider", havingValue = "toss")
    // public PgClient tossPgClient(TossProperties tossProperties) {
    //     return new TossPgClient(tossProperties);
    // }
}
