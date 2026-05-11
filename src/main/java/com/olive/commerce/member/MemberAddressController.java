package com.olive.commerce.member;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 배송지 CRUD API. OLV-012.
 *
 * GET /api/me/addresses — 배송지 목록
 * POST /api/me/addresses — 배송지 추가
 * PATCH /api/me/addresses/{id} — 배송지 수정
 * DELETE /api/me/addresses/{id} — 배송지 삭제
 */
@RestController
@RequestMapping("/api/me/addresses")
public class MemberAddressController {

    private final MemberAddressService service;

    public MemberAddressController(MemberAddressService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<MemberDtos.AddressResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        List<MemberAddress> addresses = service.listByMember(principal.memberId());
        List<MemberDtos.AddressResponse> response = addresses.stream()
            .map(this::toResponse)
            .toList();
        return ApiResponse.success(response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberDtos.AddressResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MemberDtos.CreateAddressRequest req) {
        MemberAddress addr = service.add(principal.memberId(), req);
        return ApiResponse.success(toResponse(addr));
    }

    @PatchMapping("/{id}")
    public ApiResponse<MemberDtos.AddressResponse> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long id,
            @Valid @RequestBody MemberDtos.UpdateAddressRequest req) {
        MemberAddress addr = service.update(id, principal.memberId(), req);
        return ApiResponse.success(toResponse(addr));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long id) {
        service.delete(id, principal.memberId());
    }

    private MemberDtos.AddressResponse toResponse(MemberAddress a) {
        return new MemberDtos.AddressResponse(
            a.getId(),
            a.getRecipientName(),
            a.getPhone(),
            a.getZipcode(),
            a.getAddressMain(),
            a.getAddressDetail(),
            a.isDefault()
        );
    }
}
