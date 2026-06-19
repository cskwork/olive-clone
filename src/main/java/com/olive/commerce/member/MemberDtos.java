package com.olive.commerce.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 회원 프로필 및 배송지 DTO.
 * OLV-012: 마이페이지 CRUD 엔드포인트용.
 */
public class MemberDtos {

    /** 프로필 응답 (GET /api/me) */
    public record ProfileResponse(
        long memberId,
        String email,
        String name,
        String phone,
        String grade,
        String role
    ) {}

    /**
     * 마이페이지 요약 응답 (GET /api/me/summary).
     * 포인트, 사용 가능 쿠폰 수, 전체 주문 수, 등급명을 담습니다.
     */
    public record SummaryResponse(
        BigDecimal pointBalance,
        long usableCouponCount,
        long totalOrderCount,
        String grade
    ) {}

    /** 프로필 수정 요청 (PATCH /api/me) */
    public record UpdateProfileRequest(
        @NotNull(message = "이름은 필수입니다")
        @Size(max = 100, message = "이름은 100자 이내여야 합니다")
        String name,

        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "전화번호 형식이 올바르지 않습니다")
        String phone
    ) {}

    /** 배송지 응답 */
    public record AddressResponse(
        long id,
        String recipientName,
        String phone,
        String zipcode,
        String addressMain,
        String addressDetail,
        boolean isDefault
    ) {}

    /** 배송지 생성 요청 */
    public record CreateAddressRequest(
        @NotBlank(message = "수령인 이름은 필수입니다")
        @Size(max = 100, message = "수령인 이름은 100자 이내여야 합니다")
        String recipientName,

        @NotBlank(message = "전화번호는 필수입니다")
        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "전화번호 형식이 올바르지 않습니다")
        String phone,

        @NotBlank(message = "우편번호는 필수입니다")
        @Size(max = 10, message = "우편번호는 10자 이내여야 합니다")
        String zipcode,

        @NotBlank(message = "기본 주소는 필수입니다")
        @Size(max = 255, message = "기본 주소는 255자 이내여야 합니다")
        String addressMain,

        @Size(max = 255, message = "상세 주소는 255자 이내여야 합니다")
        String addressDetail,

        boolean isDefault
    ) {}

    /** 배송지 수정 요청 */
    public record UpdateAddressRequest(
        @NotBlank(message = "수령인 이름은 필수입니다")
        @Size(max = 100, message = "수령인 이름은 100자 이내여야 합니다")
        String recipientName,

        @NotBlank(message = "전화번호는 필수입니다")
        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "전화번호 형식이 올바르지 않습니다")
        String phone,

        @NotBlank(message = "우편번호는 필수입니다")
        @Size(max = 10, message = "우편번호는 10자 이내여야 합니다")
        String zipcode,

        @NotBlank(message = "기본 주소는 필수입니다")
        @Size(max = 255, message = "기본 주소는 255자 이내여야 합니다")
        String addressMain,

        @Size(max = 255, message = "상세 주소는 255자 이내여야 합니다")
        String addressDetail,

        boolean isDefault
    ) {}
}
