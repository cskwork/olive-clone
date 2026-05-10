package com.olive.commerce.common.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class RepositoryIntegrationTest extends PostgresIntegrationSupport {

    @Autowired
    private EntityManager entityManager;

    @Test
    void postgresDialectIsActive() {
        Object value = entityManager.createNativeQuery("SELECT version()").getSingleResult();
        assertThat(value.toString()).startsWith("PostgreSQL 16");
    }

    @Test
    void flywayBaselineIsApplied() {
        Number applied = (Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
            .getSingleResult();
        assertThat(applied.intValue()).isGreaterThanOrEqualTo(1);
    }
}
