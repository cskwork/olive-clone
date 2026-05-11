package com.olive.commerce.auth;

import com.olive.commerce.auth.AuthDtos.LoginRequest;
import com.olive.commerce.auth.AuthDtos.LoginResponse;
import com.olive.commerce.auth.AuthDtos.RefreshRequest;
import com.olive.commerce.auth.AuthDtos.SignupRequest;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.common.security.JwtClaims;
import com.olive.commerce.common.security.JwtTokenProvider;
import com.olive.commerce.common.security.JwtValidationException;
import com.olive.commerce.member.Member;
import com.olive.commerce.member.MemberGradeRepository;
import com.olive.commerce.member.MemberLoginHistory;
import com.olive.commerce.member.MemberLoginHistoryRepository;
import com.olive.commerce.member.MemberRefreshToken;
import com.olive.commerce.member.MemberRefreshTokenRepository;
import com.olive.commerce.member.MemberRepository;
import com.olive.commerce.member.MemberRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * /api/auth 4 흐름의 도메인 서비스.
 *
 * Service 가 단일인 이유: 4 흐름 모두 같은 의존(Member/Token repo, encoder, jwt,
 * audit, guard)을 공유하고 흐름 간 경계가 얇다. 다섯 번째 흐름 (social-login,
 * 2FA 등) 이 들어올 때 분리하는 것이 common-rules 의 "premature abstraction
 * 회피" 원칙과 일치 (Explore Recommendation).
 */
@Service
public class AuthService {

    private static final String DEFAULT_GRADE = "BRONZE";

    private final MemberRepository memberRepo;
    private final MemberRefreshTokenRepository refreshRepo;
    private final MemberLoginHistoryRepository historyRepo;
    private final MemberGradeRepository gradeRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwt;
    private final LoginAttemptGuard guard;
    private final AuditLogger auditLogger;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    /**
     * timing-attack 완화용 더미 bcrypt 해시. 진짜 bcrypt 형식이어야 matches() 가
     * IllegalArgumentException 없이 정상 비교를 흉내낸다 (없으면 매 실패마다
     * "Encoded password does not look like BCrypt" warn 로그가 남는다).
     */
    private final String dummyBcryptHash;

    public AuthService(MemberRepository memberRepo,
                       MemberRefreshTokenRepository refreshRepo,
                       MemberLoginHistoryRepository historyRepo,
                       MemberGradeRepository gradeRepo,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwt,
                       LoginAttemptGuard guard,
                       AuditLogger auditLogger,
                       Clock clock,
                       @Value("${olive.security.jwt.access-ttl}") Duration accessTtl,
                       @Value("${olive.security.jwt.refresh-ttl}") Duration refreshTtl) {
        this.memberRepo = memberRepo;
        this.refreshRepo = refreshRepo;
        this.historyRepo = historyRepo;
        this.gradeRepo = gradeRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.guard = guard;
        this.auditLogger = auditLogger;
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.dummyBcryptHash = passwordEncoder.encode("__user_enumeration_dummy__");
    }

    @Transactional
    public long signup(SignupRequest req) {
        if (memberRepo.existsByEmail(req.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED, "email=" + req.email());
        }
        Long gradeId = gradeRepo.findIdByName(DEFAULT_GRADE);
        if (gradeId == null) {
            throw new IllegalStateException("default grade " + DEFAULT_GRADE + " not seeded");
        }
        String hash = passwordEncoder.encode(req.password());
        Member m = Member.newSignup(req.email(), hash, req.name(), req.phone(), gradeId);
        try {
            return memberRepo.saveAndFlush(m).getId();
        } catch (DataIntegrityViolationException race) {
            // race: 동시 signup. 두 번째 호출은 UNIQUE 충돌로 떨어진다.
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED, "email=" + req.email());
        }
    }

    /**
     * 트랜잭션을 의도적으로 걸지 않는다 — 실패 응답 시점에 BusinessException 이
     * 던져지면 동일 트랜잭션의 member_login_histories INSERT 까지 롤백되어
     * 감사 트레일이 사라진다 (PRD §16.2 위반). 각 repository 호출은 Spring Data
     * 의 기본 @Transactional 로 독립 커밋된다.
     */
    public LoginResponse login(LoginRequest req, String ip, String userAgent) {
        // 1. 이미 잠겨 있으면 비밀번호 검증 자체를 건너뛴다 — DDoS 방지.
        if (guard.isLocked(req.email())) {
            recordHistory(null, ip, userAgent, false, "ACCOUNT_LOCKED");
            audit("LOGIN_FAILURE", Map.of(
                "email", req.email(), "ip", nullToEmpty(ip), "reason", "ACCOUNT_LOCKED"));
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "email=" + req.email());
        }

        Optional<Member> opt = memberRepo.findByEmail(req.email());
        if (opt.isEmpty()) {
            // 잠금 카운트는 알려진 이메일에 대해서만 의미있게 — 알려지지 않은
            // 이메일은 timing 측면에서 비밀번호 비교는 흉내낸다 (encode/match cost).
            passwordEncoder.matches(req.password(), dummyBcryptHash);
            recordHistory(null, ip, userAgent, false, "BAD_CREDENTIALS");
            audit("LOGIN_FAILURE", Map.of(
                "email", req.email(), "ip", nullToEmpty(ip), "reason", "BAD_CREDENTIALS"));
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS, "unknown email");
        }
        Member m = opt.get();
        if (!"ACTIVE".equals(m.getStatus())) {
            recordHistory(m.getId(), ip, userAgent, false, "ACCOUNT_LOCKED");
            audit("LOGIN_FAILURE", Map.of(
                "memberId", m.getId(), "ip", nullToEmpty(ip), "reason", "ACCOUNT_LOCKED"));
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "memberId=" + m.getId());
        }
        if (!passwordEncoder.matches(req.password(), m.getPasswordHash())) {
            boolean lockedNow = guard.recordFailure(req.email());
            String reason = lockedNow ? "ACCOUNT_LOCKED" : "BAD_CREDENTIALS";
            recordHistory(m.getId(), ip, userAgent, false, reason);
            audit("LOGIN_FAILURE", Map.of(
                "memberId", m.getId(), "ip", nullToEmpty(ip), "reason", reason));
            ErrorCode code = lockedNow ? ErrorCode.ACCOUNT_LOCKED : ErrorCode.BAD_CREDENTIALS;
            throw new BusinessException(code, "memberId=" + m.getId());
        }

        guard.clearFailures(req.email());
        recordHistory(m.getId(), ip, userAgent, true, null);
        audit("LOGIN_SUCCESS", Map.of(
            "memberId", m.getId(), "ip", nullToEmpty(ip)));

        return issueTokens(m.getId(), MemberRole.USER);
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest req) {
        JwtClaims claims;
        try {
            claims = jwt.parseRefresh(req.refreshToken());
        } catch (JwtValidationException ex) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, ex.getMessage());
        }
        long memberId = claims.memberId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String hash = RefreshTokens.sha256Hex(req.refreshToken());

        MemberRefreshToken stored = refreshRepo.lockByTokenHash(hash)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INVALID_REFRESH_TOKEN, "not found or replayed"));
        if (!stored.isActive(now) || !stored.getMemberId().equals(memberId)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, "revoked or expired");
        }
        stored.revoke(now);
        return issueTokens(memberId, MemberRole.USER);
    }

    @Transactional
    public int logout(long memberId) {
        return refreshRepo.revokeAllActiveByMemberId(memberId, OffsetDateTime.now(clock));
    }

    private LoginResponse issueTokens(long memberId, MemberRole role) {
        String access = jwt.issueAccess(memberId, role);
        String refresh = jwt.issueRefresh(memberId);
        OffsetDateTime expires = OffsetDateTime.now(clock).plus(refreshTtl);
        refreshRepo.save(MemberRefreshToken.issue(
            memberId, RefreshTokens.sha256Hex(refresh), expires));
        return new LoginResponse(access, refresh, accessTtl.toSeconds());
    }

    private void recordHistory(Long memberId, String ip, String ua, boolean success, String reason) {
        MemberLoginHistory h = success
            ? MemberLoginHistory.success(memberId, ip, ua)
            : MemberLoginHistory.failure(memberId, ip, ua, reason);
        historyRepo.save(h);
    }

    private void audit(String event, Map<String, Object> base) {
        Map<String, Object> attrs = new HashMap<>(base);
        auditLogger.log(event, attrs);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
