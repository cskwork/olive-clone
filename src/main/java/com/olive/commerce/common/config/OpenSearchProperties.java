package com.olive.commerce.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("olive.opensearch")
public record OpenSearchProperties(List<String> uris) {
}
