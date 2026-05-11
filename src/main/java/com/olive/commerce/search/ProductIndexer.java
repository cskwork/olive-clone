package com.olive.commerce.search;

import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductRepository;
import jakarta.persistence.EntityManager;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Product → OpenSearch 인덱싱. wiki §95-search-domain의 9개 필드 모양 그대로
 * {@link ProductDocument}로 변환 후 bulk index.
 *
 * <p>호출자는 두 군데:
 * <ul>
 *   <li>{@link OutboxIndexerWorker} — 변경 이벤트 드레이너 (배치 100건).</li>
 *   <li>{@link ReindexProductsCommand} — {@code ./gradlew reindexProducts}.</li>
 * </ul>
 *
 * <p>실패 시 {@link IndexingException}을 던져 워커가 attempt_count를 증가시키도록 한다.
 */
@Component
public class ProductIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);
    public static final int BULK_SIZE = 100;

    private final OpenSearchClient client;
    private final ProductRepository productRepository;
    private final EntityManager em;

    public ProductIndexer(
        OpenSearchClient client,
        ProductRepository productRepository,
        EntityManager em
    ) {
        this.client = client;
        this.productRepository = productRepository;
        this.em = em;
    }

    /**
     * 입력된 productId 묶음을 OpenSearch에 bulk index한다. 입력의 일부 ID는
     * DB에 없을 수도 있는데(삭제됨), 그 경우 OS에서도 같은 _id로 delete한다.
     *
     * @return 처리된 ID 목록(성공 + 시도) — 실패는 throw로 보고.
     */
    @Transactional(readOnly = true)
    public List<Long> indexBulk(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (productIds.size() > BULK_SIZE) {
            throw new IllegalArgumentException(
                "productIds size " + productIds.size() + " exceeds BULK_SIZE " + BULK_SIZE);
        }

        List<Product> products = productRepository.findAllById(productIds);
        Map<Long, Product> byId = new HashMap<>();
        for (Product p : products) {
            byId.put(p.getId(), p);
        }

        Map<Long, List<String>> categoryNamesById = loadCategoryNames(productIds);

        List<BulkOperation> ops = new ArrayList<>(productIds.size());
        for (Long id : productIds) {
            Product product = byId.get(id);
            if (product == null) {
                ops.add(BulkOperation.of(b -> b.delete(d -> d
                    .index(ProductDocument.INDEX_NAME)
                    .id(String.valueOf(id))
                )));
            } else {
                ProductDocument doc = toDocument(product, categoryNamesById.getOrDefault(id, List.of()));
                ops.add(BulkOperation.of(b -> b.index(i -> i
                    .index(ProductDocument.INDEX_NAME)
                    .id(String.valueOf(doc.productId()))
                    .document(doc)
                )));
            }
        }

        try {
            BulkResponse response = client.bulk(BulkRequest.of(b -> b.operations(ops)));
            if (response.errors()) {
                StringBuilder sb = new StringBuilder("OpenSearch bulk reported per-item errors:");
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        sb.append(" [_id=").append(item.id())
                          .append(" reason=").append(item.error().reason()).append("]");
                    }
                }
                throw new IndexingException(sb.toString());
            }
            log.debug("Bulk indexed {} products into OpenSearch", productIds.size());
            return productIds;
        } catch (OpenSearchException | IOException e) {
            throw new IndexingException("OpenSearch bulk failed: " + e.getMessage(), e);
        }
    }

    /**
     * 단건 인덱싱 — 어드민 수동 재인덱스에서 사용.
     */
    public List<Long> indexSingle(Long productId) {
        return indexBulk(List.of(productId));
    }

    private ProductDocument toDocument(Product product, List<String> categoryNames) {
        BigDecimal salePrice = product.getSalePrice() != null ? product.getSalePrice() : product.getBasePrice();
        return new ProductDocument(
            product.getId(),
            product.getName(),
            product.getBrand() != null ? product.getBrand().getName() : null,
            categoryNames,
            List.of(),                    // tags: 도메인 미구현 (wiki §95 follow-up).
            salePrice.longValueExact(),
            0.0f,                          // rating: review 도메인 미구현.
            0L,                             // salesCount: 통계 도메인 미구현.
            0L,                             // reviewCount: review 도메인 미구현.
            product.getStatus().name()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<Long, List<String>> loadCategoryNames(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        List<Object[]> rows = em.createNativeQuery("""
                SELECT pcm.product_id, c.name
                FROM product_category_mapping pcm
                JOIN categories c ON c.id = pcm.category_id
                WHERE pcm.product_id IN (:ids)
                """)
            .setParameter("ids", productIds)
            .getResultList();

        Map<Long, List<String>> byProduct = new HashMap<>();
        for (Object[] row : rows) {
            Long pid = ((Number) row[0]).longValue();
            String name = (String) row[1];
            byProduct.computeIfAbsent(pid, k -> new ArrayList<>()).add(name);
        }
        return byProduct;
    }
}
