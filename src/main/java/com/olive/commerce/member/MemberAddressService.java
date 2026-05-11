package com.olive.commerce.member;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 배송지 관리 서비스. OLV-012.
 *
 * 소유권 검증, 기본 배송지 트랜잭션 일관성, 유일 배송지 삭제 방지 로직 포함.
 */
@Service
public class MemberAddressService {

    private final MemberAddressRepository addresses;

    public MemberAddressService(MemberAddressRepository addresses) {
        this.addresses = addresses;
    }

    /** 회원의 모든 배송지 조회 (기본 배송지 먼저) */
    public List<MemberAddress> listByMember(Long memberId) {
        return addresses.findByMemberIdOrderByDefaultFirst(memberId);
    }

    /** 새 배송지 추가. isDefault=true면 기존 기본 배송지를 false로 변경. */
    @Transactional
    public MemberAddress add(Long memberId, MemberDtos.CreateAddressRequest req) {
        if (req.isDefault()) {
            addresses.clearDefaultByMemberId(memberId);
        }

        MemberAddress addr = MemberAddress.newAddress(
            memberId,
            req.recipientName(),
            req.phone(),
            req.zipcode(),
            req.addressMain(),
            req.addressDetail(),
            req.isDefault()
        );
        return addresses.save(addr);
    }

    /** 배송지 수정. 소유권 검증 후 필드 업데이트. */
    @Transactional
    public MemberAddress update(Long addressId, Long memberId, MemberDtos.UpdateAddressRequest req) {
        MemberAddress addr = findOwned(addressId, memberId);

        if (req.isDefault()) {
            addresses.clearDefaultByMemberId(memberId);
        }

        addr.setRecipientName(req.recipientName());
        addr.setPhone(req.phone());
        addr.setZipcode(req.zipcode());
        addr.setAddressMain(req.addressMain());
        addr.setAddressDetail(req.addressDetail());
        addr.setDefault(req.isDefault());

        return addresses.save(addr);
    }

    /** 배송지 삭제. 유일 배송지인 경우 차단. */
    @Transactional
    public void delete(Long addressId, Long memberId) {
        MemberAddress addr = findOwned(addressId, memberId);

        // 회원의 배송지가 1개뿐인지 확인
        long count = addresses.countByMemberId(memberId);
        if (count <= 1) {
            throw new BusinessException(ErrorCode.CANNOT_DELETE_ONLY_ADDRESS,
                "memberId=" + memberId + ", addressId=" + addressId);
        }

        addresses.delete(addr);
    }

    /** 소유권 검증: 주소의 memberId와 현재 사용자의 memberId가 일치해야 함 */
    private MemberAddress findOwned(Long addressId, Long memberId) {
        MemberAddress addr = addresses.findById(addressId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND,
                "addressId=" + addressId));

        if (!addr.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_OWNED,
                "addressId=" + addressId + ", memberId=" + memberId);
        }
        return addr;
    }
}
