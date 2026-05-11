package com.olive.commerce.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 회원 등급 read-only 미러. V2 마이그레이션이 BRONZE/SILVER/GOLD 시드를 INSERT 했으므로
 * 본 엔티티는 조회 전용으로만 사용한다 (회원가입 시 BRONZE id 조회).
 */
@Entity
@Table(name = "member_grades")
public class MemberGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "discount_rate", nullable = false)
    private BigDecimal discountRate;

    @Column(name = "point_rate", nullable = false)
    private BigDecimal pointRate;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected MemberGrade() {}

    public Long getId() { return id; }
    public String getName() { return name; }
}
