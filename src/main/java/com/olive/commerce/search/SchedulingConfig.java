package com.olive.commerce.search;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link OutboxIndexerWorker}의 {@code @Scheduled} fixedDelay 폴링을 활성화.
 *
 * <p>{@code test} 프로필에서는 비활성: JVM-싱글톤 Postgres + Spring 컨텍스트
 * 캐시 + 다중 SpringBootTest가 결합되면 다른 컨텍스트의 워커가 PENDING row를
 * 가로채 OLV-100 IT가 비결정적으로 실패한다. OLV-100 IT는 워커를 수동
 * 트리거하므로 정확한 동작 검증에는 영향 없다.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
