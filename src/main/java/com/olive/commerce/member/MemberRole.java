package com.olive.commerce.member;

/**
 * 회원 역할 5단계.
 *
 * Hierarchy (RoleHierarchyConfig 와 동기):
 *   SUPER_ADMIN > {PRODUCT_ADMIN, ORDER_ADMIN, CS_MANAGER}
 *   PRODUCT_ADMIN/ORDER_ADMIN/CS_MANAGER > USER
 *
 * Spring Security 권한 문자열은 "ROLE_" 접두사 규약을 따른다 (authority() 참조).
 * 출처: llm-wiki/10-member-domain.md, llm-wiki/98-security.md.
 */
public enum MemberRole {
    USER,
    CS_MANAGER,
    PRODUCT_ADMIN,
    ORDER_ADMIN,
    SUPER_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
