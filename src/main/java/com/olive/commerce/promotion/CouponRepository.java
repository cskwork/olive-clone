package com.olive.commerce.promotion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 쿠폰 Repository.
 * <p>동시성 제어를 위한 {@code FOR UPDATE} 쿼리를 제공합니다.
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 활성 쿠폰 목록을 조회합니다.
     *
     * @param status 쿠폰 상태
     * @return 활성 쿠폰 목록
     */
    List<Coupon> findByStatus(String status);

    /**
     * ID로 쿠폰을 조회하며 비관적 잠금을 획득합니다 (FOR UPDATE).
     * <p>대량 발급 시 {@code issued_count} 갱신의 원자성을 보장하기 위해 사용합니다.
     *
     * @param id 쿠폰 ID
     * @return 잠금이 적용된 쿠폰
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

    /**
     * 회원 쿠폰 목록을 쿠폰 ID로 조회합니다.
     *
     * @param couponId 쿠폰 ID
     * @return 해당 쿠폰의 회원 쿠폰 목록
     */
    @Query("SELECT mc FROM MemberCoupon mc WHERE mc.couponId = :couponId")
    List<MemberCoupon> findAllMemberCoupons(@Param("couponId") Long couponId);
}
