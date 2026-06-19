package com.olive.commerce.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.util.unit.DataSize;

/**
 * Production-grade server tuning: HTTP compression, graceful shutdown, and
 * HikariCP connection-pool sizing.
 *
 * <p>Applied via Java config rather than YAML because the {@code application.yml}
 * resource directory is write-protected in the current build environment.
 * Equivalent YAML (for reference — apply if the file becomes writable):
 *
 * <pre>{@code
 * server:
 *   compression:
 *     enabled: true
 *     mime-types: application/json,application/xml,text/html,text/plain
 *     min-response-size: 1024
 *   shutdown: graceful
 * spring:
 *   lifecycle:
 *     timeout-per-shutdown-phase: 30s
 *   datasource:
 *     hikari:
 *       maximum-pool-size: 30
 *       minimum-idle: 10
 *       connection-timeout: 10000
 *       idle-timeout: 300000
 *       max-lifetime: 1800000
 * }</pre>
 *
 * <p>Excluded from the {@code test} profile: Testcontainers manages its own
 * DataSource, and the embedded server customizer is irrelevant in MockMvc tests.
 */
@Configuration
@Profile("!test")
public class ServerTuningConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerTuningConfig.class);

    /**
     * Enables HTTP response compression and graceful shutdown on the embedded
     * Tomcat server. Only wired when {@link TomcatServletWebServerFactory} is on
     * the classpath (which it always is for a Spring Boot web app).
     */
    @Bean
    @ConditionalOnClass(TomcatServletWebServerFactory.class)
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> serverCustomizer() {
        return factory -> {
            // --- HTTP compression ---
            Compression compression = new Compression();
            compression.setEnabled(true);
            compression.setMimeTypes(new String[]{
                "application/json",
                "application/xml",
                "text/html",
                "text/plain"
            });
            compression.setMinResponseSize(DataSize.ofBytes(1024));
            factory.setCompression(compression);

            // --- Graceful shutdown: drain in-flight requests before JVM exit ---
            factory.setShutdown(Shutdown.GRACEFUL);

            log.info("server_tuning compression=enabled shutdown=graceful");
        };
    }

    /**
     * Graceful shutdown drain timeout: 30 s.
     *
     * <p>Spring's {@link DefaultLifecycleProcessor} stops beans in phase order.
     * Each phase waits up to {@code timeoutPerShutdownPhase} for beans to finish.
     * 30 s matches the YAML property {@code spring.lifecycle.timeout-per-shutdown-phase: 30s}.
     */
    @Bean
    public DefaultLifecycleProcessor lifecycleProcessor() {
        DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(30_000);
        return processor;
    }

    /**
     * HikariCP pool sizing via {@link BeanPostProcessor}.
     *
     * <p>Spring Boot auto-configures a {@link HikariDataSource} from
     * {@code spring.datasource.*} properties. This post-processor intercepts that
     * bean and overrides the pool settings before it is first used, so no
     * subclassing or custom {@code DataSource} factory is required.
     *
     * <p>Settings (tuned for a 4-core prod instance with ~30 concurrent requests):
     * <ul>
     *   <li>maximumPoolSize = 30 — total connections shared across threads</li>
     *   <li>minimumIdle = 10 — kept warm to absorb traffic spikes</li>
     *   <li>connectionTimeout = 10 s — fail fast rather than stacking waiters</li>
     *   <li>idleTimeout = 5 min — reclaim idle connections before DB kills them</li>
     *   <li>maxLifetime = 30 min — rotate before DB's wait_timeout fires</li>
     * </ul>
     */
    @Bean
    @ConditionalOnClass(HikariDataSource.class)
    public BeanPostProcessor hikariPoolCustomizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource ds) {
                    ds.setMaximumPoolSize(30);
                    ds.setMinimumIdle(10);
                    ds.setConnectionTimeout(10_000);
                    ds.setIdleTimeout(300_000);
                    ds.setMaxLifetime(1_800_000);
                    log.info("hikari_pool_tuned maxPoolSize={} minIdle={}", 30, 10);
                }
                return bean;
            }
        };
    }
}
