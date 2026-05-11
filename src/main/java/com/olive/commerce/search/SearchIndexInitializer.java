package com.olive.commerce.search;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 부팅 시 OpenSearch {@code products} 인덱스를 보장한다.
 *
 * <p>이미 존재하면 no-op. 매핑은 wiki §95-search-domain 스펙: productName(text),
 * tags/categoryNames/brandName/status(keyword), salePrice/salesCount
 * /reviewCount(long), rating(float).
 *
 * <p>한국어 분석기({@code nori})는 도커 기본 이미지에 미포함이므로 standard
 * 분석기로 시작한다. nori 도입은 후속 ticket (현 wiki에 follow-up 명시).
 *
 * <p>OpenSearch 다운 시에도 앱이 죽지 않게 모든 예외는 WARN 로깅으로 흡수.
 * 워커가 다음 tick에서 자연스럽게 재시도하지만, 인덱스 자체가 없으면 indexer가
 * 자동 생성하지 못하므로 부팅 후 인프라 복구 시 어드민이 재기동을 트리거해야 한다.
 */
@Component
@Order(0)
public class SearchIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final OpenSearchClient client;

    public SearchIndexInitializer(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureProductsIndex();
        } catch (Exception e) {
            // 부팅을 막지 않는다 — OpenSearch가 떠 있지 않아도 도메인 트래픽은 계속 처리.
            log.warn("Failed to ensure OpenSearch products index on startup (workers will retry on next run)", e);
        }
    }

    public void ensureProductsIndex() throws IOException {
        boolean exists = client.indices()
            .exists(ExistsRequest.of(b -> b.index(ProductDocument.INDEX_NAME)))
            .value();
        if (exists) {
            log.debug("OpenSearch index already exists: {}", ProductDocument.INDEX_NAME);
            return;
        }

        TypeMapping mapping = TypeMapping.of(m -> m
            .properties("productId",   Property.of(p -> p.long_(l -> l)))
            .properties("productName", Property.of(p -> p.text(t -> t.analyzer("standard"))))
            .properties("brandName",    Property.of(p -> p.keyword(k -> k)))
            .properties("categoryNames", Property.of(p -> p.keyword(k -> k)))
            .properties("tags",         Property.of(p -> p.keyword(k -> k)))
            .properties("salePrice",    Property.of(p -> p.long_(l -> l)))
            .properties("rating",       Property.of(p -> p.float_(f -> f)))
            .properties("salesCount",   Property.of(p -> p.long_(l -> l)))
            .properties("reviewCount",  Property.of(p -> p.long_(l -> l)))
            .properties("status",       Property.of(p -> p.keyword(k -> k)))
        );

        client.indices().create(CreateIndexRequest.of(b -> b
            .index(ProductDocument.INDEX_NAME)
            .settings(s -> s
                .numberOfShards("1")
                .numberOfReplicas("0")
            )
            .mappings(mapping)
        ));
        log.info("Created OpenSearch index: {} (analyzer=standard, follow-up: switch to nori once image is built with plugin)", ProductDocument.INDEX_NAME);
    }
}
