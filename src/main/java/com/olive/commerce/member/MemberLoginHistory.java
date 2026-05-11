package com.olive.commerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "member_login_histories")
public class MemberLoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "login_at", insertable = false, updatable = false)
    private OffsetDateTime loginAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "failure_reason", length = 50)
    private String failureReason;

    protected MemberLoginHistory() {}

    public static MemberLoginHistory success(Long memberId, String ip, String ua) {
        MemberLoginHistory h = new MemberLoginHistory();
        h.memberId = memberId;
        h.ipAddress = ip;
        h.userAgent = ua;
        h.success = true;
        return h;
    }

    public static MemberLoginHistory failure(Long memberId, String ip, String ua, String reason) {
        MemberLoginHistory h = new MemberLoginHistory();
        h.memberId = memberId;
        h.ipAddress = ip;
        h.userAgent = ua;
        h.success = false;
        h.failureReason = reason;
        return h;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public OffsetDateTime getLoginAt() { return loginAt; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
}
