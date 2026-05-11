package com.olive.commerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(classes = AwsS3IntegrationTest.TestApp.class)
class AwsS3IntegrationTest {

    private static final String BUCKET = "olv003-smoke";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3")
    ).withServices(Service.S3);

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(Service.S3).toString());
        registry.add("aws.s3.region", LOCALSTACK::getRegion);
        registry.add("aws.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("aws.s3.secret-key", LOCALSTACK::getSecretKey);
        registry.add("aws.s3.bucket", () -> BUCKET);
    }

    @Autowired
    private S3Client s3Client;

    @Test
    void putAndGetRoundtrip() throws Exception {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(BUCKET)
                .key("smoke.txt")
                .metadata(Map.of("env", "test"))
                .build(),
            RequestBody.fromString("hello-olv003")
        );

        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
            GetObjectRequest.builder().bucket(BUCKET).key("smoke.txt").build()
        )) {
            assertThat(new String(response.readAllBytes())).isEqualTo("hello-olv003");
        }
    }

    @Configuration
    @EnableConfigurationProperties(AwsS3Properties.class)
    @Import(AwsS3Config.class)
    static class TestApp {
    }
}
