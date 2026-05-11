package com.olive.commerce.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aws.s3")
public record AwsS3Properties(
    String endpoint,
    String region,
    String bucket,
    String accessKey,
    String secretKey
) {
}
