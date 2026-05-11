package com.olive.commerce.promotion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 회원 쿠폰 Repository.
 */
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

    /**
     * 회원의 쿠폰 목록을 상태별로 조회합니다.
     *
     * @param memberId 회원 ID
     * @param status   쿠폰 상태
     * @return 회원 쿠폰 목록
     */
    List<MemberCoupon> findByMemberIdAndStatus(Long memberId, String status);

    /**
     * 회원의 모든 쿠폰을 조회합니다.
     *
     * @param memberId 회원 ID
     * @return 회원의 모든 쿠폰
     */
    List<MemberCoupon> findByMemberId(Long memberId);

    /**
     * 회원이 특정 쿠폰을 발급받았는지 확인합니다 (ISSUED 상태만).
     *
     * @param memberId 회원 ID
     * @param couponId 쿠폰 ID
     * @return 발급된 회원 쿠폰 (없으면 empty)
     */
    @Query("SELECT mc FROM MemberCoupon mc WHERE mc.memberId = :memberId AND mc.couponId = :couponId AND mc.status = 'ISSUED'")
    Optional<MemberCoupon> findIssuedByMemberIdAndCouponId(@Param("memberId") Long memberId, @Param("couponId") Long couponId);

    /**
     * 회원 쿠폰으로 쿠폰 정보를 함께 조회합니다 (fetch join).
     *
     * @param id 회원 쿠폰 ID
     * @return 회원 쿠폰 (쿠폰 정보 포함)
     */
    @Query("SELECT mc FROM MemberCoupon mc JOIN FETCH mc.coupon WHERE mc.id = :id")
    Optional<MemberCoupon> findByIdWithCoupon(@Param("id") Long id);
}
