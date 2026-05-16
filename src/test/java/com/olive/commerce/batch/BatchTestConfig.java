package com.olive.commerce.batch;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * 배치 작업 테스트용 설정.
 * <p>
 * delivery.client 패키지의 CarrierClient와 충돌을 방지하기 위해 제외합니다.
 */
@TestConfiguration
@ComponentScan(
    basePackages = "com.olive.commerce",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.olive\\.commerce\\.delivery\\.client\\..*"
    )
)
public class BatchTestConfig {
}
