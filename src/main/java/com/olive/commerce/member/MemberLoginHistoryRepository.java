package com.olive.commerce.member;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberLoginHistoryRepository extends JpaRepository<MemberLoginHistory, Long> {
}
