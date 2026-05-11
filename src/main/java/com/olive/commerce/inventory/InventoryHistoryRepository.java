package com.olive.commerce.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * InventoryHistory Repository (append-only audit trail).
 */
public interface InventoryHistoryRepository extends JpaRepository<InventoryHistory, Long> {

    /**
     * 특정 옵션의 모든 변경 이력 조회 (최신순).
     */
    List<InventoryHistory> findByProductOptionIdOrderByCreatedAtDesc(Long productOptionId);
}
