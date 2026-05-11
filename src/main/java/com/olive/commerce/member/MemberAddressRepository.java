package com.olive.commerce.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MemberAddressRepository extends JpaRepository<MemberAddress, Long> {

    /** 회원의 모든 배송지. 기본 배송지 먼저. */
    @Query("SELECT a FROM MemberAddress a WHERE a.memberId = :memberId ORDER BY a.isDefault DESC, a.id ASC")
    List<MemberAddress> findByMemberIdOrderByDefaultFirst(Long memberId);

    /** 회원의 모든 배송지 (is_default 업데이트용). */
    List<MemberAddress> findByMemberId(Long memberId);

    /** 회원의 배송지 개수 (삭제 가능 여부 확인용). */
    long countByMemberId(Long memberId);

    /** 회원의 기존 기본 배송지를 모두 false로 변경. */
    @Modifying
    @Query("UPDATE MemberAddress a SET a.isDefault = false WHERE a.memberId = :memberId AND a.isDefault = true")
    int clearDefaultByMemberId(Long memberId);
}
