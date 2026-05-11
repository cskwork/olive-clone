package com.olive.commerce.member;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface MemberRefreshTokenRepository extends JpaRepository<MemberRefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MemberRefreshToken t where t.tokenHash = :hash")
    Optional<MemberRefreshToken> lockByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("update MemberRefreshToken t set t.revokedAt = :now "
        + "where t.memberId = :memberId and t.revokedAt is null")
    int revokeAllActiveByMemberId(@Param("memberId") long memberId,
                                   @Param("now") OffsetDateTime now);
}
