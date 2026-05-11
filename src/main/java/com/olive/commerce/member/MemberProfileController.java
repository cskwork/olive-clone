package com.olive.commerce.member;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 마이페이지 프로필 API. OLV-012.
 *
 * GET /api/me — 현재 회원의 프로필 조회
 * PATCH /api/me — 프로필 수정 (이름, 전화번호)
 */
@RestController
@RequestMapping("/api/me")
public class MemberProfileController {

    private final MemberRepository members;
    private final MemberGradeRepository grades;

    public MemberProfileController(MemberRepository members, MemberGradeRepository grades) {
        this.members = members;
        this.grades = grades;
    }

    @GetMapping
    public ApiResponse<MemberDtos.ProfileResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + principal.memberId()));

        MemberGrade g = grades.findById(m.getGradeId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_GRADE_NOT_FOUND, "gradeId=" + m.getGradeId()));

        return ApiResponse.success(new MemberDtos.ProfileResponse(
            m.getId(),
            m.getEmail(),
            m.getName(),
            m.getPhone(),
            g.getName(),
            principal.role().name()
        ));
    }

    @PatchMapping
    public ApiResponse<MemberDtos.ProfileResponse> updateProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody MemberDtos.UpdateProfileRequest req) {
        Member m = members.findById(principal.memberId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_NOT_FOUND, "memberId=" + principal.memberId()));

        m.updateProfile(req.name(), req.phone());
        members.save(m);

        MemberGrade g = grades.findById(m.getGradeId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.MEMBER_GRADE_NOT_FOUND, "gradeId=" + m.getGradeId()));

        return ApiResponse.success(new MemberDtos.ProfileResponse(
            m.getId(),
            m.getEmail(),
            m.getName(),
            m.getPhone(),
            g.getName(),
            principal.role().name()
        ));
    }
}
