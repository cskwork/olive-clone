package com.olive.commerce.public_api;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.product.ProductRepository;
import com.olive.commerce.search.OutboxIndexerWorker;
import com.olive.commerce.search.ProductDocument;
import com.olive.commerce.search.ProductIndexEnqueuer;
import com.olive.commerce.search.SearchIndexInitializer;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-101 AC1/AC2/AC3 — 키워드 검색 + 카테고리 필터 + OpenSearch 다운 시 503.
 *
 * <p>{@link ProductSearchSyncIT}의 패턴을 그대로 활용해 인덱스를 워커 수동 trigger로 채우고,
 * 컨트롤러 레벨에서 HTTP 호출까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SearchApiIT extends PostgresIntegrationSupport {

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
    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        tx = new TransactionTemplate(txManager);
        indexInitializer.ensureProductsIndex();
        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM outbox_events").executeUpdate());
        openSearchClient.deleteByQuery(d -> d
            .index(ProductDocument.INDEX_NAME)
            .query(q -> q.matchAll(m -> m))
            .refresh(true)
        );
        // V3 seed의 선크림 product를 인덱스에 채워 둔다.
        Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());
        tx.executeWithoutResult(s -> indexEnqueuer.enqueueProductIndexSync(productId));
        worker.drainOnce();
        openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
    }

    @Test
    void ac1_keyword_선크림_returnsOnSaleProducts() throws Exception {
        mockMvc.perform(get("/api/search/products").param("keyword", "선크림"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].productName", containsString("선크림")))
            .andExpect(jsonPath("$.meta.total").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void ac2_keywordAndCategoryFilter_returnsIntersection() throws Exception {
        // V3 seed에서 '스킨케어' 카테고리 ID는 1.
        mockMvc.perform(get("/api/search/products")
                .param("keyword", "선크림")
                .param("categoryId", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.total").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data[0].productName", containsString("선크림")));
    }

    @Test
    void ac2_keywordAndNonMatchingCategoryFilter_returnsEmpty() throws Exception {
        // 존재하지 않는 카테고리 id → intersection 0.
        mockMvc.perform(get("/api/search/products")
                .param("keyword", "선크림")
                .param("categoryId", "99999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void ac3_openSearchDown_returns503WithDocumentedBody() throws Exception {
        OPENSEARCH.getDockerClient().pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        try {
            mockMvc.perform(get("/api/search/products").param("keyword", "선크림"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("검색 서비스를 일시적으로 사용할 수 없습니다."));
        } finally {
            OPENSEARCH.getDockerClient().unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }
    }
}
