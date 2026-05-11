package com.olive.commerce.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 로그인 실패 카운트 + 계정 잠금을 Redis 로 관리한다 (PRD §14, ticket §Hints).
 *
 * - 실패 카운트: key=auth:fail:{email}, TTL 10분
 * - 잠금 마커  : key=auth:lock:{email}, TTL 15분, 5회 연속 실패 시 SET NX
 *
 * members 테이블의 status 컬럼을 갱신하지 않는 이유: 일시적 잠금이 영구 상태
 * 컬럼을 흔들면 감사 로그·복구 비용이 비싸진다 (10-member-domain.md).
 */
@Component
public class LoginAttemptGuard {

    static final String FAIL_KEY_PREFIX = "auth:fail:";
    static final String LOCK_KEY_PREFIX = "auth:lock:";

    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration failWindow;
    private final Duration lockDuration;

    public LoginAttemptGuard(StringRedisTemplate redis,
                             @Value("${olive.auth.lock.max-attempts:5}") int maxAttempts,
                             @Value("${olive.auth.lock.fail-window:PT10M}") Duration failWindow,
                             @Value("${olive.auth.lock.duration:PT15M}") Duration lockDuration) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.failWindow = failWindow;
        this.lockDuration = lockDuration;
    }

    public boolean isLocked(String email) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY_PREFIX + email));
    }

    /**
     * 실패 카운트를 1 증가시키고 임계 도달 시 잠금을 SET. 임계 도달 여부 반환.
     */
    public boolean recordFailure(String email) {
        String failKey = FAIL_KEY_PREFIX + email;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, failWindow);
        }
        if (count != null && count >= maxAttempts) {
            redis.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + email, "1", lockDuration);
            redis.delete(failKey);
            return true;
        }
        return false;
    }

    public void clearFailures(String email) {
        redis.delete(FAIL_KEY_PREFIX + email);
    }
}
