package com.olive.commerce.promotion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 원장 Repository.
 * <p>잔액 계산, 내역 조회, 주문별 내역 조회를 제공합니다.
 */
public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /**
     * 회원의 포인트 사용 가능 금액을 합계합니다.
     * <p>EARN + CANCEL + ADMIN_ADJUST 합계에서 USE + EXPIRE 합계를 뺍니다.
     * 유효 기간 내(available_at <= asOf AND (expires_at IS NULL OR expires_at > asOf))인 내역만 대상.
     *
     * @param memberId 회원 ID
     * @param asOf     기준 시점
     * @return 사용 가능 금액 합계
     */
    @Query(value = """
        SELECT COALESCE(SUM(
            CASE
                WHEN change_type IN ('EARN', 'CANCEL', 'ADMIN_ADJUST') THEN amount
                WHEN change_type IN ('USE', 'EXPIRE') THEN -amount
                ELSE 0
            END
        ), 0.00)
        FROM point_histories
        WHERE member_id = :memberId
          AND available_at <= :asOf
          AND (expires_at IS NULL OR expires_at > :asOf)
        """, nativeQuery = true)
    java.math.BigDecimal sumSpendableBalance(@Param("memberId") Long memberId, @Param("asOf") OffsetDateTime asOf);

    /**
     * 회원의 대기 중인 포인트를 조회합니다 (asOf < available_at <= cutoff).
     *
     * @param memberId 회원 ID
     * @param asOf     기준 시점
     * @param cutoff   마감 시점
     * @return 대기 중인 포인트 내역
     */
    @Query("SELECT h FROM PointHistory h WHERE h.memberId = :memberId AND h.availableAt > :asOf AND h.availableAt <= :cutoff AND h.changeType = 'EARN'")
    List<PointHistory> findPendingByMemberIdBetween(@Param("memberId") Long memberId, @Param("asOf") OffsetDateTime asOf, @Param("cutoff") OffsetDateTime cutoff);

    /**
     * 회원의 포인트 내역을 최신순으로 페이징 조회합니다.
     *
     * @param memberId 회원 ID
     * @param pageable 페이징 정보
     * @return 포인트 내역 페이지
     */
    @Query("SELECT h FROM PointHistory h WHERE h.memberId = :memberId ORDER BY h.createdAt DESC")
    Page<PointHistory> findByMemberIdOrderByCreatedAtDesc(@Param("memberId") Long memberId, Pageable pageable);

    /**
     * 주문 ID로 포인트 내역을 조회합니다 (취소용).
     *
     * @param orderId 주문 ID
     * @return 포인트 내역 목록
     */
    List<PointHistory> findByOrderId(Long orderId);

    /**
     * 만료될 적립 내역을 조회합니다 (expires_at <= asOf AND changeType = EARN).
     * <p>해당 내역에 대해 EXPIRE 행을 생성하기 위해 사용합니다.
     *
     * @param asOf 기준 시점
     * @return 만료될 적립 내역
     */
    @Query("SELECT h FROM PointHistory h WHERE h.expiresAt <= :asOf AND h.changeType = 'EARN'")
    List<PointHistory> findExpiredEarns(@Param("asOf") OffsetDateTime asOf);

    /**
     * 회원의 포인트 행을 잠금 조회합니다 (동시성 제어용).
     * <p>points 테이블에 직접 접근하기 위한 네이티브 쿼리입니다.
     *
     * @param memberId 회원 ID
     * @return 포인트 행 (없으면 빈 Optional)
     */
    @Query(value = "SELECT * FROM points WHERE member_id = :memberId FOR UPDATE", nativeQuery = true)
    Optional<Object> lockByMemberIdForUpdate(@Param("memberId") Long memberId);
}
