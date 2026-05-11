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

    /**
     * 클라이언트-측 타임아웃 (ms). 본 값을 명시하지 않으면 Apache HttpClient 4 default가
     * "무제한"이라 OpenSearch 노드가 hang 시 워커 스레드가 영원히 막힌다(=OLV-100 IT
     * 학습). 짧게 잡아 다음 fixedDelay tick에서 자연 재시도가 가능하도록.
     */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int SOCKET_TIMEOUT_MS = 3_000;

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        List<String> uris = Objects.requireNonNull(properties.uris(), "olive.opensearch.uris must be set");
        HttpHost[] hosts = uris.stream()
            .map(HttpHost::create)
            .toArray(HttpHost[]::new);

        this.restClient = RestClient.builder(hosts)
            .setRequestConfigCallback(builder -> builder
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS))
            .build();
        OpenSearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        OpenSearchClient client = new OpenSearchClient(transport);
        log.info("OpenSearch client initialized (uris={}, connectMs={}, socketMs={})",
            uris, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
        return client;
    }

    @PreDestroy
    void closeRestClient() throws IOException {
        if (restClient != null) {
            restClient.close();
        }
    }
}
