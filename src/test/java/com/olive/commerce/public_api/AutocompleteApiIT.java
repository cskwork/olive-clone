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

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OLV-101 AC4 — autocomplete prefix matches (case-insensitive).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AutocompleteApiIT extends PostgresIntegrationSupport {

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
        // Redis 캐시 비우기.
        var keys = redisTemplate.keys("cache:search:autocomplete:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);

        // V3 seed 인덱싱.
        Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());
        tx.executeWithoutResult(s -> indexEnqueuer.enqueueProductIndexSync(productId));
        worker.drainOnce();
        openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
    }

    @Test
    void ac4_korean_prefix_returnsMatches() throws Exception {
        // 한국어는 case 영향 없음. seed product 이름 "키즈 매일 선크림 SPF50+ PA++++"를 prefix "선크"로 잡는다.
        mockMvc.perform(get("/api/search/autocomplete").param("prefix", "선크"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.suggestions", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(jsonPath("$.data.suggestions", everyItem(containsStringIgnoringCase("선크"))));
    }

    @Test
    void ac4_english_prefix_isCaseInsensitive() throws Exception {
        // Brand name "더샘"은 한글이지만, productName에 "SPF50+" 포함 → 대소문자 무관.
        mockMvc.perform(get("/api/search/autocomplete").param("prefix", "spf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.suggestions", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(jsonPath("$.data.suggestions[*]", everyItem(containsStringIgnoringCase("spf"))));
    }
}
