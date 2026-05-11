package com.olive.commerce.common.config;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchConfig.class);

    private RestClient restClient;

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        List<String> uris = Objects.requireNonNull(properties.uris(), "olive.opensearch.uris must be set");
        HttpHost[] hosts = uris.stream()
            .map(HttpHost::create)
            .toArray(HttpHost[]::new);

        this.restClient = RestClient.builder(hosts).build();
        OpenSearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        OpenSearchClient client = new OpenSearchClient(transport);
        log.info("OpenSearch client initialized (uris={})", uris);
        return client;
    }

    @PreDestroy
    void closeRestClient() throws IOException {
        if (restClient != null) {
            restClient.close();
        }
    }
}
