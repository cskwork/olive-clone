package com.olive.commerce.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 주문 Repository.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 회원 ID로 주문 목록 조회 (최신순).
     */
    @Query("SELECT o FROM Order o WHERE o.memberId = :memberId ORDER BY o.createdAt DESC")
    java.util.List<Order> findByMemberIdOrderByCreatedAtDesc(@Param("memberId") Long memberId);

    /**
     * 회원 주문 목록 조회 (페이지네이션 + 상태 필터) (OLV-063).
     *
     * @param memberId 회원 ID
     * @param status 주문 상태 (null이면 전체)
     * @param pageable 페이지네이션
     * @return 주문 페이지
     */
    @Query("SELECT o FROM Order o WHERE o.memberId = :memberId " +
            "AND (:status IS NULL OR o.status = :status) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findByMemberIdAndStatus(
            @Param("memberId") Long memberId,
            @Param("status") String status,
            Pageable pageable
    );

    /**
     * 관리자 주문 목록 조회 (페이지네이션 + 다중 필터) (OLV-063).
     *
     * @param status 주문 상태 (null이면 전체)
     * @param memberId 회원 ID (null이면 전체)
     * @param from 시작일시 (null이면 무제한)
     * @param to 종료일시 (null이면 무제한)
     * @param pageable 페이지네이션
     * @return 주문 페이지
     */
    @Query("SELECT o FROM Order o WHERE " +
            "(:status IS NULL OR o.status = :status) AND " +
            "(:memberId IS NULL OR o.memberId = :memberId) AND " +
            "(:from IS NULL OR o.createdAt >= :from) AND " +
            "(:to IS NULL OR o.createdAt <= :to) " +
            "ORDER BY o.createdAt DESC")
    Page<Order> findByFilters(
            @Param("status") String status,
            @Param("memberId") Long memberId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable
    );

    /**
     * order_no로 주문 조회.
     */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * Idempotency-Key로 이미 처리된 주문을 조회 (OLV-061 멱등성).
     * <p>
     * order_status_histories 테이블의 reason 필드에 idempotency key를 저장하여 검증합니다.
     */
    @Query("""
        SELECT o FROM Order o
        WHERE o.id = (
            SELECT h.order.id FROM OrderStatusHistory h
            WHERE h.reason = :idempotencyKey
            AND h.changedByKind = com.olive.commerce.order.OrderStatusHistory.ChangedByKind.SYSTEM
            ORDER BY h.createdAt DESC
            LIMIT 1
        )
        """)
    Optional<Order> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * ID로 주문을 조회하고 orderNo를 반환 (DB 트리거로 생성된 값).
     */
    @Query(value = "SELECT order_no FROM orders WHERE id = :id", nativeQuery = true)
    String findOrderNoById(@Param("id") Long id);

    /**
     * 회원 ID와 배송지 ID로 주문을 조회하여 배송지 정보를 포함합니다 (OLV-063).
     *
     * @param orderId 주문 ID
     * @return 주문 (배송지 정보 포함)
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.deliveryAddress WHERE o.id = :orderId")
    Optional<Order> findByIdWithDeliveryAddress(@Param("orderId") Long orderId);

    /**
     * 주문 번호로 주문과 배송지 정보를 조회합니다 (OLV-063).
     *
     * @param orderNo 주문 번호
     * @return 주문 (배송지 정보 포함)
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.deliveryAddress WHERE o.orderNo = :orderNo")
    Optional<Order> findByOrderNoWithDeliveryAddress(@Param("orderNo") String orderNo);

    /**
     * 주문 ID로 주문과 연관된 엔티티들을 조회합니다 (OLV-063).
     *
     * @param orderId 주문 ID
     * @return 주문 (items, deliveryAddress 포함)
     */
    @Query("SELECT o FROM Order o " +
            "LEFT JOIN FETCH o.items " +
            "LEFT JOIN FETCH o.deliveryAddress " +
            "WHERE o.id = :orderId")
    Optional<Order> findByIdWithItemsAndDelivery(@Param("orderId") Long orderId);
}
