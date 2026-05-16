package com.olive.commerce.search;

import com.olive.commerce.common.metrics.CommerceMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 검색 메트릭 기록 헬퍼 (OLV-130).
 *
 * <p>SearchService의 검색 요청을 래핑하여 메트릭을 기록한다.
 */
@Component
@RequiredArgsConstructor
public class SearchMetrics {

    private static final Logger log = LoggerFactory.getLogger(SearchMetrics.class);

    private final CommerceMetrics metrics;

    /**
     * 검색 결과를 래핑하여 메트릭을 기록한다.
     *
     * @param sample 타이머 샘플
     * @param result 검색 결과
     * @return 원본 검색 결과
     */
    public SearchService.SearchResult recordSearch(Timer.Sample sample, SearchService.SearchResult result) {
        metrics.recordSearch(sample);
        if (result.items().isEmpty()) {
            metrics.searchEmptyResult();
            log.debug("Metric recorded: search_empty_result");
        }
        return result;
    }
}
