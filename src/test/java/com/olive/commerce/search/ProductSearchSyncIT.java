package com.olive.commerce.search;

import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.testcontainers.OpensearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OLV-100 AC 검증 — outbox 발행 + 워커가 OpenSearch 문서를 반영하는가.
 *
 * <p>{@code @Scheduled}는 {@code test} 프로필에서 비활성화되어 있으므로
 * 본 테스트는 {@link OutboxIndexerWorker#drainOnce()}를 직접 호출해 워커 한 tick을
 * 결정론적으로 시뮬레이션한다. 프로덕션 부팅에서는 1초 fixedDelay로 자동 호출.
 */
@SpringBootTest
@Testcontainers
class ProductSearchSyncIT extends PostgresIntegrationSupport {

    @Container
    static final OpensearchContainer<?> OPENSEARCH = new OpensearchContainer<>(
        DockerImageName.parse("opensearchproject/opensearch:2.15.0")
    );

    @DynamicPropertySource
    static void registerOpenSearch(DynamicPropertyRegistry registry) {
        registry.add("olive.opensearch.uris", () -> List.of(OPENSEARCH.getHttpHostAddress()));
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6379);
    }

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductIndexEnqueuer indexEnqueuer;

    @Autowired
    private OutboxIndexerWorker worker;

    @Autowired
    private SearchIndexInitializer indexInitializer;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        tx = new TransactionTemplate(txManager);
        indexInitializer.ensureProductsIndex();
        // 매 테스트 클린 슬레이트.
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            em.createNativeQuery(
                "UPDATE products SET sale_price = 20000, base_price = 25000 WHERE name LIKE '%선크림%'")
                .executeUpdate();
        });
        openSearchClient.deleteByQuery(d -> d
            .index(ProductDocument.INDEX_NAME)
            .query(q -> q.matchAll(m -> m))
            .refresh(true)
        );
    }

    @Test
    void enqueueAndDrain_reflectsProductInOpenSearch() throws Exception {
        Long productId = tx.execute(s -> {
            Product seed = productRepository.findAll().get(0); // V3 seed 선크림
            indexEnqueuer.enqueueProductIndexSync(seed.getId());
            return seed.getId();
        });

        int processed = worker.drainOnce();
        assertThat(processed).isEqualTo(1);

        openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
        GetResponse<ProductDocument> response = openSearchClient.get(g -> g
            .index(ProductDocument.INDEX_NAME)
            .id(String.valueOf(productId)),
            ProductDocument.class
        );
        assertThat(response.found()).isTrue();
        ProductDocument doc = response.source();
        assertThat(doc).isNotNull();
        assertThat(doc.productId()).isEqualTo(productId);
        assertThat(doc.productName()).contains("선크림");
        assertThat(doc.salePrice()).isEqualTo(20000L);
        assertThat(doc.status()).isEqualTo("ON_SALE");
        assertThat(doc.brandName()).isEqualTo("더샘");
        assertThat(doc.categoryNames()).contains("스킨케어");

        // outbox row가 DONE 상태인지 확인.
        OutboxEvent finalRow = outboxRepository
            .findByAggregate("PRODUCT", productId, org.springframework.data.domain.PageRequest.of(0, 1))
            .get(0);
        assertThat(finalRow.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DONE);
        assertThat(finalRow.getAttemptCount()).isZero();
    }

    @Test
    void priceUpdate_propagatesNewSalePriceInsideFiveSeconds() {
        Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());

        BigDecimal newPrice = new BigDecimal("17777");
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("UPDATE products SET sale_price = :p WHERE id = :id")
                .setParameter("p", newPrice)
                .setParameter("id", productId)
                .executeUpdate();
            indexEnqueuer.enqueueProductIndexSync(productId);
        });

        int processed = worker.drainOnce();
        assertThat(processed).isEqualTo(1);

        try {
            openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
            GetResponse<ProductDocument> response = openSearchClient.get(g -> g
                .index(ProductDocument.INDEX_NAME)
                .id(String.valueOf(productId)),
                ProductDocument.class
            );
            assertThat(response.found()).isTrue();
            assertThat(response.source()).isNotNull();
            assertThat(response.source().salePrice()).isEqualTo(17777L);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void openSearchDown_outboxAttemptsAccumulateAndNeverCrash() throws Exception {
        // OpenSearch 컨테이너를 멈춰 다운 상태 모방. pause는 connection을 hang 시키므로
        // OpenSearchConfig의 socketTimeout=3s가 있어야 짧게 실패한다 — 본 티켓에서 추가.
        OPENSEARCH.getDockerClient().pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        try {
            Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());
            tx.executeWithoutResult(s -> indexEnqueuer.enqueueProductIndexSync(productId));

            // 워커를 직접 한 tick 호출 — 예외 catch 후 attempt_count++.
            int processed = worker.drainOnce();
            assertThat(processed).isEqualTo(1);

            // 앱이 죽지 않았다는 사실 검증.
            assertThat(productRepository.count()).isPositive();

            OutboxEvent failed = outboxRepository
                .findByAggregate("PRODUCT", productId, org.springframework.data.domain.PageRequest.of(0, 1))
                .get(0);
            assertThat(failed.getAttemptCount()).isEqualTo(1);
            assertThat(failed.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
            assertThat(failed.isDlq()).isFalse();
        } finally {
            OPENSEARCH.getDockerClient().unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }

        // 복구 후 워커 재호출하면 drain 성공 → 같은 product가 OS에 색인.
        Long productId = tx.execute(s -> productRepository.findAll().get(0).getId());
        int processed = worker.drainOnce();
        assertThat(processed).isEqualTo(1);

        openSearchClient.indices().refresh(RefreshRequest.of(r -> r.index(ProductDocument.INDEX_NAME)));
        GetResponse<ProductDocument> recovered = openSearchClient.get(g -> g
            .index(ProductDocument.INDEX_NAME)
            .id(String.valueOf(productId)),
            ProductDocument.class
        );
        assertThat(recovered.found()).isTrue();
    }
}
