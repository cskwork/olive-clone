package com.olive.commerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 회원 배송지. PRD §6.1.
 * OLV-010에서 member_addresses 테이블 생성 완료.
 */
@Entity
@Table(name = "member_addresses")
public class MemberAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "zipcode", nullable = false)
    private String zipcode;

    @Column(name = "address_main", nullable = false)
    private String addressMain;

    @Column(name = "address_detail")
    private String addressDetail;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MemberAddress() {}

    public static MemberAddress newAddress(Long memberId, String recipientName, String phone,
                                          String zipcode, String addressMain, String addressDetail,
                                          boolean isDefault) {
        MemberAddress a = new MemberAddress();
        a.memberId = memberId;
        a.recipientName = recipientName;
        a.phone = phone;
        a.zipcode = zipcode;
        a.addressMain = addressMain;
        a.addressDetail = addressDetail;
        a.isDefault = isDefault;
        return a;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getZipcode() { return zipcode; }
    public String getAddressMain() { return addressMain; }
    public String getAddressDetail() { return addressDetail; }
    public boolean isDefault() { return isDefault; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setZipcode(String zipcode) { this.zipcode = zipcode; }
    public void setAddressMain(String addressMain) { this.addressMain = addressMain; }
    public void setAddressDetail(String addressDetail) { this.addressDetail = addressDetail; }
}
