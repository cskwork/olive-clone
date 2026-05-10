/**
 * 재고 도메인 — product_option_id 단위 재고, reserve→commit, Redis 분산 락 +
 * DB row 락 fallback (llm-wiki/30-inventory-domain.md, PRD §20.3·§20.5).
 */
package com.olive.commerce.inventory;
