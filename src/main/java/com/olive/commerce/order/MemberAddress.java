package com.olive.commerce.order;

/**
 * 배송지 정보 참조 (Order에서 참조하는 용도).
 * <p>
 * Note: 실제 엔티티는 {@link com.olive.commerce.member.MemberAddress}입니다.
 * 이 클래스는 중복 엔티티 문제를 피하기 위해 @Entity 어노테이션을 제거했습니다.
 *
 * @deprecated member 패키지의 {@link com.olive.commerce.member.MemberAddress}를 사용하세요.
 */
@Deprecated
// @Entity - 제외됨: member 패키지의 MemberAddress 사용
// @Table(name = "member_addresses") - 제외됨
class _OrderMemberAddressStub {
    // This class is kept as a placeholder to avoid file not found errors.
    // Use com.olive.commerce.member.MemberAddress instead.
}
