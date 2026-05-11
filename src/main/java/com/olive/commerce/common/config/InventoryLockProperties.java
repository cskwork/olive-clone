package com.olive.commerce.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Inventory 도메인의 분산 락 설정 (OLV-031).
 *
 * <p>application.yml에서 {@code inventory.lock.*} 네임스페이스로 설정한다.
 */
@Component
@ConfigurationProperties("inventory.lock")
public class InventoryLockProperties {

    /**
     * 락 획득 대기 시간(초). 기본 2초 (PRD §10.2).
     * 락을 2초 내에 획득하지 못하면 {@code INSUFFICIENT_INVENTORY} 또는
     * {@code LOCK_ACQUISITION_FAILED} 에러를 반환한다.
     */
    private int lockWaitTimeSeconds = 2;

    /**
     * 락 임대 시간(초). 기본 5초 (PRD §10.2).
     * 락을 획득한 후 5초 내에 작업을 완료하지 못하면 자동으로 해제된다.
     * 서비스 프로세스가 중단되어도 락이 방출되도록 보장한다.
     */
    private int lockLeaseTimeSeconds = 5;

    /**
     * Redis 다운 시 DB 락({@code SELECT ... FOR UPDATE})으로 폴백할지 여부.
     * 기본 {@code false} — Redis 다운 시 에러를 반환하고 재시도를 유도한다.
     */
    private boolean fallbackToDb = false;

    public int getLockWaitTimeSeconds() {
        return lockWaitTimeSeconds;
    }

    public void setLockWaitTimeSeconds(int lockWaitTimeSeconds) {
        this.lockWaitTimeSeconds = lockWaitTimeSeconds;
    }

    public int getLockLeaseTimeSeconds() {
        return lockLeaseTimeSeconds;
    }

    public void setLockLeaseTimeSeconds(int lockLeaseTimeSeconds) {
        this.lockLeaseTimeSeconds = lockLeaseTimeSeconds;
    }

    public boolean isFallbackToDb() {
        return fallbackToDb;
    }

    public void setFallbackToDb(boolean fallbackToDb) {
        this.fallbackToDb = fallbackToDb;
    }
}
