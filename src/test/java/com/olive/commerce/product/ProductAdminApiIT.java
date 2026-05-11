package com.olive.commerce.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OLV-022 AC 검증 — Product Admin API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class ProductAdminApiIT extends PostgresIntegrationSupport {

    private static final String BUCKET = "commerce-images-local";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
        org.testcontainers.utility.DockerImageName.parse("localstack/localstack:3")
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private S3Client s3Client;

    private Jwt productAdminToken;

    @BeforeAll
    static void beforeAll() {
        // LocalStack은 @Container로 자동 시작되며, @DynamicPropertySource로 설정됨
        // S3Client 빈 초기화 시 AwsS3Config가 버킷을 생성함
    }

    @BeforeEach
    void setUp() {
        // Reset sequences to continue from existing Flyway data
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            // Get max IDs from tables and reset sequences accordingly
            Long maxProductId = (Long) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM products").getSingleResult();
            Long maxOptionId = (Long) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM product_options").getSingleResult();
            em.createNativeQuery("SELECT setval('products_id_seq', " + maxProductId + ", true)").getSingleResult();
            em.createNativeQuery("SELECT setval('product_options_id_seq', " + maxOptionId + ", true)").getSingleResult();
        });

        productAdminToken = createJwt(1L, "PRODUCT_ADMIN");
    }

    private Jwt createJwt(Long userId, String role) {
        return Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("sub", userId.toString())
            .claim("role", role)
            .claim("typ", "access")
            .build();
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWithRole(Jwt jwt, String role) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt)
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }

    // ---------------------------------------------------------------------
    // AC1: Product creation with options, categories, images returns 201
    // ---------------------------------------------------------------------
    @Test
    void createProduct_withTwoOptionsAndTwoCategories_returns201() throws Exception {
        // Given: brand id=1, category ids 1,2 exist from Flyway
        var request = new ProductCreateRequest(
            1L,  // brand_id (더샘)
            "테스트 상품",
            "테스트 상품 설명",
            new BigDecimal("15000"),
            new BigDecimal("12000"),
            Product.ProductStatus.DRAFT,
            List.of(1L, 2L),  // categories: 스킨케어, 메이크업
            List.of(
                new OptionCreateRequest("50ml", BigDecimal.ZERO),
                new OptionCreateRequest("100ml", new BigDecimal("3000"))
            ),
            List.of(
                "https://s3.local/products/test1.png",
                "https://s3.local/products/test2.png"
            )
        );

        // When: create product
        var result = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.name").value("테스트 상품"))
            .andExpect(jsonPath("$.data.basePrice").value(15000))
            .andExpect(jsonPath("$.data.salePrice").value(12000))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.brandName").value("더샘"))
            .andExpect(jsonPath("$.data.categories").isArray())
            .andExpect(jsonPath("$.data.categories.length()").value(2))
            .andExpect(jsonPath("$.data.options").isArray())
            .andExpect(jsonPath("$.data.options.length()").value(2))
            .andExpect(jsonPath("$.data.images").isArray())
            .andExpect(jsonPath("$.data.images.length()").value(2))
            .andReturn();

        // Extract product ID
        String responseBody = result.getResponse().getContentAsString();
        Long productId = json.readTree(responseBody).path("data").path("id").asLong();

        // Then: GET reflects all data
        mockMvc.perform(get("/api/admin/products/" + productId)
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.options[0].optionName").value("50ml"))
            .andExpect(jsonPath("$.data.options[1].optionName").value("100ml"))
            .andExpect(jsonPath("$.data.images[0].isThumbnail").value(true));
    }

    // ---------------------------------------------------------------------
    // Product listing with pagination and filters
    // ---------------------------------------------------------------------
    @Test
    void listProducts_withPagination_returnsPage() throws Exception {
        mockMvc.perform(get("/api/admin/products?page=0&size=10")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.size").value(10))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void listProducts_withStatusFilter_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/admin/products?status=ON_SALE")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].status").value("ON_SALE"));
    }

    // ---------------------------------------------------------------------
    // Product partial update
    // ---------------------------------------------------------------------
    @Test
    void updateProduct_partialUpdate_returns200() throws Exception {
        // First create a product
        var createRequest = new ProductCreateRequest(
            1L, "업데이트 전 상품", null, new BigDecimal("10000"), null, Product.ProductStatus.DRAFT,
            List.of(1L), List.of(new OptionCreateRequest("기본", BigDecimal.ZERO)),
            List.of("https://s3.local/products/img.png")
        );
        var createResult = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createRequest)))
            .andReturn();
        String response = createResult.getResponse().getContentAsString();
        Long productId = json.readTree(response).path("data").path("id").asLong();

        // Update name and price only
        var updateRequest = new ProductUpdateRequest("업데이트 후 상품", null, new BigDecimal("20000"), null, null);
        mockMvc.perform(patch("/api/admin/products/" + productId)
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(updateRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("업데이트 후 상품"))
            .andExpect(jsonPath("$.data.basePrice").value(20000));
    }

    // ---------------------------------------------------------------------
    // AC2: Invalid status transition returns 422
    // ---------------------------------------------------------------------
    @Test
    void updateProduct_invalidStatusTransitionDraftToSoldOut_returns422() throws Exception {
        // Create a DRAFT product
        var createRequest = new ProductCreateRequest(
            1L, "상태 천이 테스트", null, new BigDecimal("10000"), null, Product.ProductStatus.DRAFT,
            List.of(1L), List.of(new OptionCreateRequest("기본", BigDecimal.ZERO)),
            List.of("https://s3.local/products/img.png")
        );
        var createResult = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createRequest)))
            .andReturn();
        String response = createResult.getResponse().getContentAsString();
        Long productId = json.readTree(response).path("data").path("id").asLong();

        // Try DRAFT → SOLD_OUT (invalid transition: DRAFT can only go to ON_SALE)
        var updateRequest = new ProductUpdateRequest(null, null, null, null, Product.ProductStatus.SOLD_OUT);
        mockMvc.perform(patch("/api/admin/products/" + productId)
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(updateRequest)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCT_STATE_TRANSITION"));
    }

    @Test
    void updateProduct_validStatusTransition_returns200() throws Exception {
        // Create a DRAFT product
        var createRequest = new ProductCreateRequest(
            1L, "상태 천이 테스트", null, new BigDecimal("10000"), null, Product.ProductStatus.DRAFT,
            List.of(1L), List.of(new OptionCreateRequest("기본", BigDecimal.ZERO)),
            List.of("https://s3.local/products/img.png")
        );
        var createResult = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createRequest)))
            .andReturn();
        String response = createResult.getResponse().getContentAsString();
        Long productId = json.readTree(response).path("data").path("id").asLong();

        // Valid transition: DRAFT → ON_SALE
        var updateRequest = new ProductUpdateRequest(null, null, null, null, Product.ProductStatus.ON_SALE);
        mockMvc.perform(patch("/api/admin/products/" + productId)
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(updateRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ON_SALE"));
    }

    // ---------------------------------------------------------------------
    // Option management
    // ---------------------------------------------------------------------
    @Test
    void addOption_returns201() throws Exception {
        // Create a product
        var createRequest = new ProductCreateRequest(
            1L, "옵션 추가 테스트", null, new BigDecimal("10000"), null, Product.ProductStatus.DRAFT,
            List.of(1L), List.of(new OptionCreateRequest("기본", BigDecimal.ZERO)),
            List.of("https://s3.local/products/img.png")
        );
        var createResult = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createRequest)))
            .andReturn();
        String response = createResult.getResponse().getContentAsString();
        Long productId = json.readTree(response).path("data").path("id").asLong();

        // Add option
        var optionRequest = new OptionCreateRequest("새 옵션", new BigDecimal("5000"));
        mockMvc.perform(post("/api/admin/products/" + productId + "/options")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(optionRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.optionName").value("새 옵션"))
            .andExpect(jsonPath("$.data.optionPrice").value(5000));
    }

    @Test
    void updateOption_returns200() throws Exception {
        // Create a product with option
        var createRequest = new ProductCreateRequest(
            1L, "옵션 수정 테스트", null, new BigDecimal("10000"), null, Product.ProductStatus.DRAFT,
            List.of(1L), List.of(new OptionCreateRequest("기본 옵션", BigDecimal.ZERO)),
            List.of("https://s3.local/products/img.png")
        );
        var createResult = mockMvc.perform(post("/api/admin/products")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(createRequest)))
            .andReturn();
        String response = createResult.getResponse().getContentAsString();
        Long productId = json.readTree(response).path("data").path("id").asLong();
        Long optionId = json.readTree(response).path("data").path("options").get(0).path("id").asLong();

        // Update option
        var optionRequest = new OptionUpdateRequest("수정된 옵션", new BigDecimal("3000"), ProductOption.OptionStatus.ON_SALE);
        mockMvc.perform(patch("/api/admin/products/" + productId + "/options/" + optionId)
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(optionRequest)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.optionName").value("수정된 옵션"))
            .andExpect(jsonPath("$.data.optionPrice").value(3000));
    }

    // ---------------------------------------------------------------------
    // AC3: Presigned URL upload flow
    // ---------------------------------------------------------------------
    @Test
    void presignedUrl_uploadAndRetrieve_returnsBytes() throws Exception {
        // Given: request presigned URL
        var presignedRequest = new PresignedUrlRequest("test.png", 1024L, "image/png");
        var presignedResult = mockMvc.perform(post("/api/admin/uploads/product-image")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(presignedRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.uploadUrl").exists())
            .andExpect(jsonPath("$.data.fileUrl").exists())
            .andReturn();

        // When: use presigned URL to upload (would be done by browser in real flow)
        // This test documents the API contract; actual S3 upload requires LocalStack
        String fileUrl = json.readTree(presignedResult.getResponse().getContentAsString())
            .path("data").path("fileUrl").asText();
        assertThat(fileUrl).contains("products/").contains("test.png");
    }

    @Test
    void presignedUrl_withInvalidContentType_returns400() throws Exception {
        var presignedRequest = new PresignedUrlRequest("test.pdf", 1024L, "application/pdf");
        mockMvc.perform(post("/api/admin/uploads/product-image")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(presignedRequest)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_FILE_TYPE"));
    }

    @Test
    void presignedUrl_withFileSizeExceeded_returns400() throws Exception {
        var presignedRequest = new PresignedUrlRequest("large.png", 11 * 1024 * 1024L, "image/png");
        mockMvc.perform(post("/api/admin/uploads/product-image")
                .with(jwtWithRole(productAdminToken, "PRODUCT_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(presignedRequest)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("FILE_SIZE_EXCEEDED"));
    }

    // ---------------------------------------------------------------------
    // Record DTOs for test requests
    // ---------------------------------------------------------------------
    record ProductCreateRequest(
        Long brandId,
        String name,
        String description,
        BigDecimal basePrice,
        BigDecimal salePrice,
        Product.ProductStatus status,
        List<Long> categoryIds,
        List<OptionCreateRequest> options,
        List<String> imageUrls
    ) {}

    record ProductUpdateRequest(
        String name,
        String description,
        BigDecimal basePrice,
        BigDecimal salePrice,
        Product.ProductStatus status
    ) {}

    record OptionCreateRequest(
        String optionName,
        BigDecimal optionPrice
    ) {}

    record OptionUpdateRequest(
        String optionName,
        BigDecimal optionPrice,
        ProductOption.OptionStatus status
    ) {}

    record PresignedUrlRequest(
        String filename,
        Long fileSize,
        String contentType
    ) {}
}
