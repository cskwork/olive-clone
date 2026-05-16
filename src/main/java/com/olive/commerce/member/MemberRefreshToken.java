package com.olive.commerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "member_refresh_tokens")
public class MemberRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String tokenHash;

    @Column(name = "issued_at", insertable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected MemberRefreshToken() {}

    public static MemberRefreshToken issue(long memberId, String tokenHash, OffsetDateTime expiresAt) {
        MemberRefreshToken t = new MemberRefreshToken();
        t.memberId = memberId;
        t.tokenHash = tokenHash;
        t.expiresAt = expiresAt;
        return t;
    }

    public void revoke(OffsetDateTime at) {
        this.revokedAt = at;
    }

    public boolean isActive(OffsetDateTime at) {
        return revokedAt == null && expiresAt.isAfter(at);
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public String getTokenHash() { return tokenHash; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
}
