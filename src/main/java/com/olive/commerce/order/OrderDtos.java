package com.olive.commerce.order;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 주문 관련 DTO (PRD §8.3).
 */
public class OrderDtos {

    /**
     * 주문 생성 요청 (PRD §8.3).
     *
     * @param items 주문 상품 목록
     * @param couponId 쿠폰 ID (선택)
     * @param usePointAmount 사용 포인트 (선택)
     * @param deliveryAddressId 배송지 ID
     */
    public record CreateOrderRequest(
            @NotEmpty(message = "주문 상품은 1개 이상이어야 합니다")
            List<OrderItemRequest> items,

            Long couponId,

            @DecimalMin(value = "0", message = "포인트는 0 이상이어야 합니다")
            BigDecimal usePointAmount,

            @NotNull(message = "배송지는 필수입니다")
            @Min(value = 1, message = "배송지 ID는 1 이상이어야 합니다")
            Long deliveryAddressId
    ) {
        public CreateOrderRequest {
            if (usePointAmount != null && usePointAmount.compareTo(BigDecimal.ZERO) < 0) {
                usePointAmount = BigDecimal.ZERO;
            }
        }

        /**
         * 주문 상품 요청.
         *
         * @param productOptionId 상품 옵션 ID
         * @param quantity 수량
         */
        public record OrderItemRequest(
                @NotNull(message = "상품 옵션 ID는 필수입니다")
                Long productOptionId,

                @NotNull(message = "수량은 필수입니다")
                @Min(value = 1, message = "수량은 1 이상이어야 합니다")
                Integer quantity
        ) {}
    }

    /**
     * 주문 생성 응답 (PRD §8.3).
     * <p>
     * 클라이언트는 이 정보를 사용하여 PG SDK를 실행하고 결제를 진행합니다.
     *
     * @param orderNo 주문 번호
     * @param paymentKey 결제 키 (payments.id)
     * @param amount 결제 금액
     * @param pgCheckoutPayload PG 체크아웃 페이로드 (선택, PG사별)
     */
    public record CreateOrderResponse(
            String orderNo,
            Long paymentKey,
            BigDecimal amount,
            PgCheckoutPayload pgCheckoutPayload
    ) {
        /**
         * PG 체크아웃 페이로드 (선택).
         * <p>
         * 실제 구현에서는 PG사별 필드가 달라질 수 있습니다.
         * 현재는 placeholder 구현입니다.
         *
         * @param pg PG사 코드 (예: KAKAOPAY, TOSS)
         * @param payload PG사별 payload
         */
        @JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
        public record PgCheckoutPayload(
                String pg,
                Object payload
        ) {}
    }

    /**
     * 주문 조회 응답 (간단 버전).
     */
    public record OrderResponse(
            Long id,
            String orderNo,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal discountAmount,
            BigDecimal pointUsedAmount,
            BigDecimal deliveryFee,
            BigDecimal finalPaymentAmount,
            List<OrderItemResponse> items,
            OffsetDateTime createdAt
    ) {}

    /**
     * 주문 상품 응답 (공통).
     * <p>
     * 모든 주문 조회 응답에서 재사용됩니다.
     */
    public record OrderItemResponse(
            Long id,
            String productName,
            String optionName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal totalAmount
    ) {}

    /**
     * 주문 취소 요청 (OLV-062).
     *
     * @param reason 취소 사유 (선택)
     */
    public record CancelOrderRequest(
            String reason
    ) {}

    /**
     * 주문 취소 응답 (OLV-062).
     *
     * @param orderId 주문 ID
     * @param orderNo 주문 번호
     * @param status 취소 후 상태 (CANCELED)
     */
    public record CancelOrderResponse(
            Long orderId,
            String orderNo,
            String status
    ) {}

    // ============================================================
    // OLV-063: 주문 조회 (User + Admin)
    // ============================================================

    /**
     * 회원 주문 목록 응답 (OLV-063).
     *
     * @param id 주문 ID
     * @param orderNo 주문 번호
     * @param status 주문 상태
     * @param totalProductAmount 총 상품 금액
     * @param finalPaymentAmount 최종 결제 금액
     * @param createdAt 주문 일시
     */
    public record MyOrderListResponse(
            Long id,
            String orderNo,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal finalPaymentAmount,
            OffsetDateTime createdAt
    ) {}

    /**
     * 회원 주문 상세 응답 (OLV-063).
     *
     * @param id 주문 ID
     * @param orderNo 주문 번호
     * @param status 주문 상태
     * @param totalProductAmount 총 상품 금액
     * @param discountAmount 할인 금액
     * @param pointUsedAmount 사용 포인트
     * @param deliveryFee 배송비
     * @param finalPaymentAmount 최종 결제 금액
     * @param items 주문 상품 목록
     * @param delivery 배송지 정보
     * @param statusHistory 상태 변경 이력
     * @param createdAt 주문 일시
     */
    public record MyOrderDetailResponse(
            Long id,
            String orderNo,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal discountAmount,
            BigDecimal pointUsedAmount,
            BigDecimal deliveryFee,
            BigDecimal finalPaymentAmount,
            List<OrderItemResponse> items,
            DeliveryInfo delivery,
            List<StatusHistoryResponse> statusHistory,
            OffsetDateTime createdAt
    ) {
        /**
         * 배송지 정보.
         *
         * @param recipientName 수령인
         * @param phone 연락처
         * @param zipcode 우편번호
         * @param addressMain 기본 주소
         * @param addressDetail 상세 주소
         */
        public record DeliveryInfo(
                String recipientName,
                String phone,
                String zipcode,
                String addressMain,
                String addressDetail
        ) {}

        /**
         * 상태 변경 이력.
         *
         * @param fromStatus 이전 상태
         * @param toStatus 변경 후 상태
         * @param reason 변경 사유
         * @param changedByKind 변경 주체 (USER/ADMIN/SYSTEM)
         * @param changedAt 변경 일시
         */
        public record StatusHistoryResponse(
                String fromStatus,
                String toStatus,
                String reason,
                String changedByKind,
                OffsetDateTime changedAt
        ) {}
    }

    /**
     * 관리자 주문 목록 응답 (OLV-063).
     * <p>
     * PII 마스킹 포함 (수령인 이름, 연락처, 주소).
     *
     * @param id 주문 ID
     * @param orderNo 주문 번호
     * @param memberId 회원 ID
     * @param status 주문 상태
     * @param totalProductAmount 총 상품 금액
     * @param finalPaymentAmount 최종 결제 금액
     * @param delivery 배송지 정보 (마스킹됨)
     * @param createdAt 주문 일시
     */
    public record AdminOrderListResponse(
            Long id,
            String orderNo,
            Long memberId,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal finalPaymentAmount,
            DeliveryInfo delivery,
            OffsetDateTime createdAt
    ) {
        /**
         * 배송지 정보 (PII 마스킹).
         *
         * @param recipientName 수령인 (마스킹: 김**)
         * @param phone 연락처 (마스킹: 010-****-1234)
         * @param address 주소 (마스킹: 서울시 **구 **동)
         */
        public record DeliveryInfo(
                String recipientName,
                String phone,
                String address
        ) {}
    }

    /**
     * 관리자 주문 상세 응답 (OLV-063).
     * <p>
     * 모든 정보 포함 (PII 마스킹 없음).
     *
     * @param id 주문 ID
     * @param orderNo 주문 번호
     * @param memberId 회원 ID
     * @param status 주문 상태
     * @param totalProductAmount 총 상품 금액
     * @param discountAmount 할인 금액
     * @param pointUsedAmount 사용 포인트
     * @param deliveryFee 배송비
     * @param finalPaymentAmount 최종 결제 금액
     * @param usedMemberCouponId 사용 쿠폰 ID
     * @param items 주문 상품 목록
     * @param delivery 배송지 정보 (전체)
     * @param statusHistory 상태 변경 이력
     * @param createdAt 주문 일시
     * @param updatedAt 수정 일시
     */
    public record AdminOrderDetailResponse(
            Long id,
            String orderNo,
            Long memberId,
            String status,
            BigDecimal totalProductAmount,
            BigDecimal discountAmount,
            BigDecimal pointUsedAmount,
            BigDecimal deliveryFee,
            BigDecimal finalPaymentAmount,
            Long usedMemberCouponId,
            List<OrderItemResponse> items,
            DeliveryInfo delivery,
            List<StatusHistoryResponse> statusHistory,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        /**
         * 배송지 정보 (전체).
         *
         * @param recipientName 수령인
         * @param phone 연락처
         * @param zipcode 우편번호
         * @param addressMain 기본 주소
         * @param addressDetail 상세 주소
         */
        public record DeliveryInfo(
                String recipientName,
                String phone,
                String zipcode,
                String addressMain,
                String addressDetail
        ) {}

        /**
         * 상태 변경 이력.
         *
         * @param fromStatus 이전 상태
         * @param toStatus 변경 후 상태
         * @param reason 변경 사유
         * @param changedByKind 변경 주체 (USER/ADMIN/SYSTEM)
         * @param changedById 변경자 ID
         * @param changedAt 변경 일시
         */
        public record StatusHistoryResponse(
                String fromStatus,
                String toStatus,
                String reason,
                String changedByKind,
                Long changedById,
                OffsetDateTime changedAt
        ) {}
    }

    /**
     * 관리자 주문 상태 변경 요청 (OLV-063).
     *
     * @param toStatus 변경할 상태
     * @param reason 변경 사유
     */
    public record StatusUpdateRequest(
            @NotBlank(message = "상태는 필수입니다")
            String toStatus,

            @NotBlank(message = "변경 사유는 필수입니다")
            String reason
    ) {}

    /**
     * 관리자 주문 상태 변경 응답 (OLV-063).
     *
     * @param orderId 주문 ID
     * @param orderNo 주문 번호
     * @param fromStatus 이전 상태
     * @param toStatus 변경 후 상태
     */
    public record StatusUpdateResponse(
            Long orderId,
            String orderNo,
            String fromStatus,
            String toStatus
    ) {}
}
