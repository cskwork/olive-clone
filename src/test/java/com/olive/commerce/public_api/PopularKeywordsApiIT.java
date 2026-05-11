package com.olive.commerce.public_api;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.product.ProductRepository;
import com.olive.commerce.search.OutboxIndexerWorker;
import com.olive.commerce.search.ProductDocument;
import com.olive.commerce.search.ProductIndexEnqueuer;
import com.olive.commerce.search.SearchIndexInitializer;
import com.olive.commerce.search.SearchPopularityAggregator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.testcontainers.OpensearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-101 AC5 — 100건 fake search seeding → popular keyword list non-empty.
 *
 * <p>{@code @Scheduled}는 {@code test} 프로필에서 꺼져 있으므로 본 IT는
 * {@link SearchPopularityAggregator#aggregateOnce()}를 수동 호출해 결정론적으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PopularKeywordsApiIT extends PostgresIntegrationSupport {

    @Container
    static final OpensearchContainer<?> OPENSEARCH = new OpensearchContainer<>(
        DockerImageName.parse("opensearchproject/opensearch:2.15.0")
    );

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("olive.opensearch.uris", () -> List.of(OPENSEARCH.getHttpHostAddress()));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OpenSearchClient openSearchClient;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductIndexEnqueuer indexEnqueuer;
    @Autowired private OutboxIndexerWorker worker;
    @Autowired private SearchIndexInitializer indexInitializer;
    @Autowired private SearchPopularityAggregator aggregator;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext private EntityManager em;

    @BeforeEach
    void setUp() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        indexInitializer.ensureProductsIndex();
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM outbox_events").executeUpdate());
        openSearchClient.deleteByQuery(d -> d
            .index(ProductDocument.INDEX_NAME)
            .query(q -> q.matchAll(m -> m))
            .refresh(true)
        );
        // Popular 누적 전부 초기화 — bucket + current.
        var bucketKeys = redisTemplate.keys("search:popular:bucket:*");
        if (bucketKeys != null && !bucketKeys.isEmpty()) redisTemplate.delete(bucketKeys);
        redisTemplate.delete("search:popular:current");

        Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());
        tx.executeWithoutResult(s -> indexEnqueuer.enqueueProductIndexSync(productId));
        worker.drainOnce();
        openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
    }

    @Test
    void ac5_seed100Searches_thenPopularEndpointReturnsNonEmpty() throws Exception {
        // /api/search/products?keyword=...를 100번 호출해 popularity bucket을 채운다.
        // 키워드 5종 × 20회 = 100건. ZSET 정렬을 확인할 수 있도록 카운트 불균형.
        String[] keywords = {"선크림", "토너", "립스틱", "샴푸", "마스카라"};
        int[] times = {40, 25, 15, 12, 8};
        int total = 0;
        for (int i = 0; i < keywords.length; i++) {
            for (int t = 0; t < times[i]; t++) {
                mockMvc.perform(get("/api/search/products").param("keyword", keywords[i]))
                    .andExpect(status().isOk());
                total++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(total).isEqualTo(100);

        // Aggregator 수동 trigger (test 프로필이라 @Scheduled가 꺼져 있음).
        aggregator.aggregateOnce();

        mockMvc.perform(get("/api/search/popular").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.keywords[0].keyword").value("선크림"))
            .andExpect(jsonPath("$.data.keywords[0].count").value(40))
            .andExpect(jsonPath("$.data.keywords.length()").value(greaterThanOrEqualTo(5)));
    }
}
