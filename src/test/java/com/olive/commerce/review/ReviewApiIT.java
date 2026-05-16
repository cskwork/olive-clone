package com.olive.commerce.review;

import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.persistence.PostgresIntegrationSupport;
import com.olive.commerce.common.security.AuthenticatedUser;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberAddress;
import com.olive.commerce.member.MemberRole;
import com.olive.commerce.order.Order;
import com.olive.commerce.order.OrderItem;
import com.olive.commerce.order.OrderRepository;
import com.olive.commerce.product.Brand;
import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductOption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리뷰 API 통합 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "aws.s3.region=us-east-1",
    "aws.s3.endpoint=http://localhost:4566",
    "olive.security.jwt.issuer=olive-commerce",
    "olive.security.jwt.access-ttl=PT30M",
    "olive.security.jwt.refresh-ttl=P14D",
    "olive.security.jwt.private-key-location=classpath:keys/app.key",
    "olive.security.jwt.public-key-location=classpath:keys/app.pub"
})
class ReviewApiIT extends PostgresIntegrationSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewService reviewService;

    private Long memberId;
    private Long productId;
    private Long productOptionId;
    private Long deliveryAddressId;
    private Long deliveredOrderItemId;
    private Long paidOrderItemId;

    private RequestPostProcessor auth() {
        AuthenticatedUser user = new AuthenticatedUser(memberId, MemberRole.USER);
        return authentication(new UsernamePasswordAuthenticationToken(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(status -> {
            // 기존 테스트 데이터 정리 (테스트 간 중복 방지)
            // 외래 키 제약 조건 순서대로 삭제 (자식 -> 부모)
            em.createQuery("DELETE FROM ReviewReport").executeUpdate();
            em.createQuery("DELETE FROM ReviewImage").executeUpdate();
            em.createQuery("DELETE FROM Review").executeUpdate();
            em.createQuery("DELETE FROM OrderItem").executeUpdate();
            em.createQuery("DELETE FROM Order").executeUpdate();
            em.createQuery("DELETE FROM ProductOption").executeUpdate();
            em.createQuery("DELETE FROM Product").executeUpdate();
            em.createQuery("DELETE FROM Brand").executeUpdate();
            em.createQuery("DELETE FROM MemberAddress").executeUpdate();
            em.createQuery("DELETE FROM Member").executeUpdate();
            em.flush();
        });

        txTemplate.executeWithoutResult(status -> {
            // 회원 생성 (외래 키 제약 조건 충족)
            Member member = Member.newSignup(
                "test@example.com",
                "hash",
                "Test User",
                "01012345678",
                1L
            );
            em.persist(member);
            em.flush();
            memberId = member.getId();

            // 배송 주소 생성 (delivery_address_id 제약 조건 충족)
            MemberAddress address = MemberAddress.newAddress(
                memberId,
                "Test Recipient",
                "01012345678",
                "12345",
                "Main Address",
                "Detail Address",
                true
            );
            em.persist(address);
            em.flush();
            deliveryAddressId = address.getId();

            // 브랜드 생성
            Brand brand = Brand.create("Test Brand", "test-brand", null);
            em.persist(brand);
            em.flush();
            Long brandId = brand.getId();

            // 상품 생성 (product_id 제약 조건 충족)
            Product product = Product.create(
                brandId,
                "Test Product",
                "Test Description",
                BigDecimal.valueOf(10000),
                BigDecimal.valueOf(9000)
            );
            em.persist(product);
            em.flush();
            productId = product.getId();

            // 상품 옵션 생성 (product_option_id 제약 조건 충족)
            ProductOption productOption = ProductOption.create("옵션1", BigDecimal.ZERO);
            productOption.setProduct(product);
            em.persist(productOption);
            em.flush();
            productOptionId = productOption.getId();

            // 배송 완료된 주문 상품 생성
            Order deliveredOrder = createOrder(memberId, deliveryAddressId, Order.OrderStatus.DELIVERED);
            OrderItem deliveredItem = createOrderItem(deliveredOrder, productId, productOptionId);
            deliveredOrderItemId = deliveredItem.getId();

            // 결제 완료된(배송 전) 주문 상품 생성
            Order paidOrder = createOrder(memberId, deliveryAddressId, Order.OrderStatus.PAID);
            OrderItem paidItem = createOrderItem(paidOrder, productId, productOptionId);
            paidOrderItemId = paidItem.getId();

            em.flush();
        });
    }

    @Test
    @DisplayName("배송 완료 후 리뷰 작성 성공")
    void writeReview_afterDelivery_returns201() throws Exception {
        mockMvc.perform(post("/api/me/reviews")
                .with(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "orderItemId": %d,
                        "rating": 5,
                        "title": "좋아요",
                        "body": "배송도 빠르고 좋습니다",
                        "imageUrls": ["https://s3.example.com/image1.jpg"]
                    }
                    """.formatted(deliveredOrderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.rating").value(5));
    }

    @Test
    @DisplayName("배송 완료 전 리뷰 작성 시 422 반환")
    void writeReview_beforeDelivery_returns422() throws Exception {
        mockMvc.perform(post("/api/me/reviews")
                .with(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "orderItemId": %d,
                        "rating": 4,
                        "title": "그냥 그래요",
                        "body": "아직 안받았는데",
                        "imageUrls": []
                    }
                    """.formatted(paidOrderItemId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.REVIEW_ELIGIBLE_ORDER_REQUIRED.name()));
    }

    @Test
    @DisplayName("이미 리뷰 작성된 주문 상품에 두 번째 리뷰 시도 시 409 반환")
    void writeReview_alreadyExists_returns409() throws Exception {
        // 첫 번째 리뷰 작성
        reviewService.writeReview(memberId, new ReviewDtos.WriteReviewRequest(
            deliveredOrderItemId, 5, "첫 리뷰", "좋아요", List.of()
        ));

        // 두 번째 리뷰 작성 시도
        mockMvc.perform(post("/api/me/reviews")
                .with(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "orderItemId": %d,
                        "rating": 4,
                        "title": "두 번째 리뷰",
                        "body": "중복 작성 시도",
                        "imageUrls": []
                    }
                    """.formatted(deliveredOrderItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.REVIEW_ALREADY_EXISTS.name()));
    }

    @Test
    @DisplayName("상품별 리뷰 목록 조회 - VISIBLE만 반환")
    void getProductReviews_returnsOnlyVisible() throws Exception {
        // 공개 리뷰 작성
        txTemplate.executeWithoutResult(status -> {
            Review review = Review.create(memberId, productId, deliveredOrderItemId, (short) 5, "좋아요", "추천합니다");
            em.persist(review);
            em.flush();
        });

        mockMvc.perform(get("/api/products/{productId}/reviews", productId)
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("리뷰 신고 성공")
    void reportReview_returns200() throws Exception {
        // 리뷰 작성 (다른 회원)
        Long[] reviewId = new Long[1];

        txTemplate.executeWithoutResult(status -> {
            // 다른 회원 생성
            Member otherMember = Member.newSignup(
                "other@example.com",
                "hash",
                "Other User",
                "01098765432",
                1L
            );
            em.persist(otherMember);
            em.flush();

            // 다른 회원의 배송 주소 생성
            MemberAddress otherAddress = MemberAddress.newAddress(
                otherMember.getId(),
                "Other Recipient",
                "01098765432",
                "54321",
                "Other Address",
                "Other Detail",
                true
            );
            em.persist(otherAddress);
            em.flush();

            // 다른 회원의 주문 생성
            Order otherOrder = createOrder(otherMember.getId(), otherAddress.getId(), Order.OrderStatus.DELIVERED);
            OrderItem otherItem = createOrderItem(otherOrder, productId, productOptionId);
            em.flush();

            Review review = Review.create(otherMember.getId(), productId, otherItem.getId(), (short) 5, "좋아요", "추천합니다");
            em.persist(review);
            em.flush();
            reviewId[0] = review.getId();
        });

        mockMvc.perform(post("/api/me/reviews/{reviewId}/report", reviewId[0])
                .with(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "reason": "부적절한 내용이 포함되어 있습니다"
                    }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reviewId").value(reviewId[0]))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("리뷰 평점 1-5 범위 검증")
    void writeReview_invalidRating_returns400() throws Exception {
        mockMvc.perform(post("/api/me/reviews")
                .with(auth())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "orderItemId": %d,
                        "rating": 6,
                        "title": "평점 초과",
                        "body": "내용",
                        "imageUrls": []
                    }
                    """.formatted(deliveredOrderItemId)))
                .andExpect(status().isBadRequest());
    }

    // Helper methods

    private Order createOrder(Long memberId, Long deliveryAddressId, Order.OrderStatus status) {
        Order order = Order.create(memberId, deliveryAddressId);
        order.setStatusDirectly(status);
        order.setOrderNo("ORD2026051300001");
        em.persist(order);
        return order;
    }

    private OrderItem createOrderItem(Order order, Long productId, Long productOptionId) {
        OrderItem item = OrderItem.create(
            order,
            productId,
            productOptionId,
            "테스트 상품",
            "옵션1",
            BigDecimal.valueOf(10000),
            1
        );
        em.persist(item);
        order.addItem(item);
        return item;
    }
}
