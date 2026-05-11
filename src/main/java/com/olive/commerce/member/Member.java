package com.olive.commerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Member() {}

    public static Member newSignup(String email, String passwordHash, String name, String phone, long gradeId) {
        Member m = new Member();
        m.email = email;
        m.passwordHash = passwordHash;
        m.name = name;
        m.phone = phone;
        m.gradeId = gradeId;
        m.status = "ACTIVE";
        return m;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getStatus() { return status; }
    public Long getGradeId() { return gradeId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
