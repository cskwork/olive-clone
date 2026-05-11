package com.olive.commerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.testcontainers.OpensearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(classes = OpenSearchIntegrationTest.TestApp.class)
class OpenSearchIntegrationTest {

    @Container
    static final OpensearchContainer<?> OPENSEARCH = new OpensearchContainer<>(
        DockerImageName.parse("opensearchproject/opensearch:2.15.0")
    );

    @DynamicPropertySource
    static void openSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("olive.opensearch.uris", () -> List.of(OPENSEARCH.getHttpHostAddress()));
    }

    @Autowired
    private OpenSearchClient client;

    @Test
    void indexCanBeCreatedAndProbed() throws Exception {
        String index = "olv003-smoke";
        client.indices().create(CreateIndexRequest.of(b -> b.index(index)));

        boolean exists = client.indices()
            .exists(ExistsRequest.of(b -> b.index(index)))
            .value();

        assertThat(exists).isTrue();
    }

    @Configuration
    @EnableConfigurationProperties(OpenSearchProperties.class)
    @Import(OpenSearchConfig.class)
    static class TestApp {
    }
}
