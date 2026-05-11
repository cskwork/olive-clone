package com.olive.commerce.common.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@Configuration
@EnableConfigurationProperties(AwsS3Properties.class)
public class AwsS3Config {

    private static final Logger log = LoggerFactory.getLogger(AwsS3Config.class);

    @Bean
    public S3Client s3Client(AwsS3Properties properties) {
        boolean localMode = properties.endpoint() != null && !properties.endpoint().isBlank();

        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(properties.region()))
            .credentialsProvider(resolveCredentials(properties))
            // LocalStack은 가상 호스트 스타일을 지원하지 않는다 — path-style URL 강제.
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

        if (localMode) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }

        S3Client client = builder.build();
        if (localMode) {
            // dev/test 한정: LocalStack init script 가 실패한 경우의 안전망.
            // 운영 AWS 에서는 devops 가 외부에서 버킷을 만든다 — 부팅 시 절대 외부 호출하지 않는다.
            ensureBucket(client, properties.bucket());
        }
        log.info(
            "S3 client initialized (endpoint={}, region={}, bucket={})",
            localMode ? properties.endpoint() : "AWS-default",
            properties.region(),
            properties.bucket()
        );
        return client;
    }

    private AwsCredentialsProvider resolveCredentials(AwsS3Properties properties) {
        if (properties.accessKey() == null || properties.accessKey().isBlank()) {
            return AnonymousCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
        );
    }

    private void ensureBucket(S3Client client, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return;
        }
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException missing) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("S3 bucket '{}' did not exist; created on startup.", bucket);
            } catch (RuntimeException createFailed) {
                log.warn("S3 bucket '{}' missing and could not be created: {}", bucket, createFailed.getMessage());
            }
        } catch (RuntimeException probeFailed) {
            log.warn("Skipping S3 bucket warm-up for '{}': {}", bucket, probeFailed.getMessage());
        }
    }
}
