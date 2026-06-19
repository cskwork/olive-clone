package com.olive.commerce.inventory;

import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.config.InventoryLockProperties;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OLV-031 admin inventory list bounded-query integration tests.
 *
 * <p>Verifies two behaviours of {@link InventoryService#findByProductId}:
 * <ol>
 *   <li>null productId throws VALIDATION_FAILED — no unbounded findAll</li>
 *   <li>valid productId returns real inventory rows (non-empty when seeded)</li>
 * </ol>
 *
 * <p>Uses Testcontainers Postgres via PostgresIntegrationSupport.
 * Inventory rows are seeded using option/product IDs already present in
 * the Flyway-migrated schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(classes = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
@Import({InventoryService.class})
@EnableConfigurationProperties(InventoryLockProperties.class)
@TestPropertySource(properties = {
    "inventory.lock.fallbackToDb=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryAdminListBoundTest extends PostgresIntegrationSupport {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockBean
    private AuditLogger auditLogger;

    @Autowired
    private EntityManager em;

    private Long seededProductId;
    private Long seededOptionId;

    @BeforeEach
    void setUp() {
        // Resolve one product and one of its options from Flyway-seeded data
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT po.id, po.product_id " +
                "FROM product_options po LIMIT 1"
        ).getSingleResult();
        seededOptionId = ((Number) row[0]).longValue();
        seededProductId = ((Number) row[1]).longValue();

        // Clean up any existing inventory row for this option, then seed fresh
        inventoryRepository.findByProductOptionId(seededOptionId)
                .ifPresent(inventoryRepository::delete);

        Inventory inv = Inventory.create(seededOptionId);
        inv.addStock(10);
        inventoryRepository.save(inv);
    }

    @AfterEach
    void tearDown() {
        inventoryRepository.findByProductOptionId(seededOptionId)
                .ifPresent(inventoryRepository::delete);
    }

    @Test
    void findByProductId_nullProductId_throwsValidationFailed() {
        // null must never fall through to findAll()
        assertThatThrownBy(() -> inventoryService.findByProductId(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("productId is required");
    }

    @Test
    void findByProductId_validProductId_returnsSeededInventoryRow() {
        // Should resolve the product's option IDs and return matching inventory rows
        List<Inventory> result = inventoryService.findByProductId(seededProductId);

        assertThat(result).isNotEmpty();
        assertThat(result)
                .anyMatch(inv -> inv.getProductOptionId().equals(seededOptionId));
    }
}
