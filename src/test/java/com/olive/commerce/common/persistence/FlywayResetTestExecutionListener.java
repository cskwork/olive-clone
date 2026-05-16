package com.olive.commerce.common.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import javax.sql.DataSource;

/**
 * Resets the shared Testcontainers database before each integration test method.
 *
 * <p>{@link PostgresIntegrationSupport} keeps one PostgreSQL container alive for
 * the JVM. That is fast, but many existing integration tests intentionally
 * truncate or mutate seed tables. Replaying Flyway outside the test transaction
 * gives every method the same schema and seed baseline.
 */
public class FlywayResetTestExecutionListener extends AbstractTestExecutionListener {

    /**
     * Run after dependency injection listeners but before Spring opens a
     * test-managed transaction.
     */
    @Override
    public int getOrder() {
        return 3500;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        DataSource dataSource = testContext.getApplicationContext().getBean(DataSource.class);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }
}
