package com.olive.commerce.payment.config;

import com.olive.commerce.payment.client.MockPgClient;
import com.olive.commerce.payment.client.PgClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * PG 클라이언트 설정.
 * olive.pg.provider 프로퍼티에 따라 실제 PG 또는 Mock PG를 주입한다.
 */
@Configuration
public class PgClientConfig {

    private final Environment environment;

    /**
     * 프로덕션용 웹훅 시크릿 가드.
     *
     * <p>배포 환경에서 반드시 {@code PG_WEBHOOK_SECRET} 환경변수(또는 Vault 경로)로
     * 시크릿을 외부화해야 한다. 기본값({@code mock-webhook-secret-for-testing})이나
     * 빈 문자열이 {@code prod} 프로파일에서 감지되면 애플리케이션 시작을 중단한다.
     */
    @Value("${olive.pg.webhook-secret:mock-webhook-secret-for-testing}")
    private String webhookSecret;

    public PgClientConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Prod-profile guard: fail fast if the webhook secret is the dev default or blank.
     * Production deployments MUST set PG_WEBHOOK_SECRET (or olive.pg.webhook-secret)
     * to a strong random value via environment variable or a secrets manager.
     */
    @PostConstruct
    void validateWebhookSecret() {
        if (environment.matchesProfiles("prod")) {
            if (webhookSecret == null
                    || webhookSecret.isBlank()
                    || MockPgClient.DEFAULT_TEST_SECRET.equals(webhookSecret)) {
                throw new IllegalStateException(
                    "olive.pg.webhook-secret must be set to a strong secret in the prod profile. "
                    + "Set the PG_WEBHOOK_SECRET environment variable. "
                    + "The default test value is not permitted in production.");
            }
        }
    }

    /**
     * Mock PG 클라이언트.
     * olive.pg.provider=mock일 때 활성화된다.
     *
     * <p>웹훅 서명 시크릿은 {@code olive.pg.webhook-secret} 프로퍼티에서 주입된다.
     * application.yml 에 개발용 기본값이 있고 application-test.yml 에 테스트용 값이 있다.
     */
    @Bean
    @ConditionalOnProperty(name = "olive.pg.provider", havingValue = "mock")
    public PgClient mockPgClient() {
        return new MockPgClient(webhookSecret);
    }

    // TODO OLV-080: 실제 PG(Toss) 연동 시 tossPgClient() 빈 추가
    // @Bean
    // @ConditionalOnProperty(name = "olive.pg.provider", havingValue = "toss")
    // public PgClient tossPgClient(TossProperties tossProperties) {
    //     return new TossPgClient(tossProperties);
    // }
}
