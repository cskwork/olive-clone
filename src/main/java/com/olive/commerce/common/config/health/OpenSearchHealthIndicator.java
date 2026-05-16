package com.olive.commerce.common.config.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.InfoResponse;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * OpenSearch health indicator.
 * <p>Cluster health API를 호출하여 상태를 검증. status가 RED가 아니면 UP,
 * RED면 DEGRADED로 간주해 readiness만 DOWN.</p>
 */
@Component
@ConditionalOnEnabledHealthIndicator("openSearch")
public class OpenSearchHealthIndicator implements HealthIndicator {

    private final OpenSearchClient client;
    private final ObjectMapper objectMapper;

    public OpenSearchHealthIndicator(OpenSearchClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Health health() {
        try {
            InfoResponse info = client.info();
            String version = info.version().number();

            var healthResponse = client.cluster().health();
            String status = healthResponse.status().jsonValue();
            int numberOfNodes = healthResponse.numberOfNodes();
            int activeShards = healthResponse.activeShards();

            if ("red".equalsIgnoreCase(status)) {
                return Health.down()
                    .withDetail("opensearch", "cluster status RED")
                    .withDetail("status", status)
                    .withDetail("version", version)
                    .withDetail("numberOfNodes", numberOfNodes)
                    .withDetail("activeShards", activeShards)
                    .build();
            }

            return Health.up()
                .withDetail("opensearch", "connected")
                .withDetail("status", status)
                .withDetail("version", version)
                .withDetail("numberOfNodes", numberOfNodes)
                .withDetail("activeShards", activeShards)
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("opensearch", "disconnected")
                .withDetail("error", e.getClass().getSimpleName())
                .withDetail("message", e.getMessage())
                .build();
        }
    }
}
