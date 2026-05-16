package com.olive.commerce.batch;

import com.olive.commerce.promotion.MemberCoupon;
import com.olive.commerce.promotion.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 쿠폰 만료 배치 작업 (PRD §17).
 * <p>
 * 매일 00:00 실행: 만료일이 지난 ISSUED 상태의 member_coupons를 EXPIRED로 변경합니다.
 */
@Component
@RequiredArgsConstructor
public class CouponExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryJob.class);

    private static final String JOB_NAME = "couponExpiry";

    private final JobRunTracker jobRunTracker;
    private final MemberCouponRepository memberCouponRepository;

    /**
     * 쿠폰 만료 처리 (매일 00:00).
     */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "CouponExpiryJob", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void expireCoupons() {
        JobRun jobRun = jobRunTracker.start(JOB_NAME, JobRun.TriggeredBy.SCHEDULED);
        int processedCount = 0;
        String errorMessage = null;

        try {
            processedCount = expireExpiredCoupons();
            jobRunTracker.complete(jobRun, processedCount);

        } catch (Exception e) {
            errorMessage = e.getMessage();
            jobRunTracker.fail(jobRun, errorMessage, processedCount);
            log.error("[{}] Job execution failed: {}", JOB_NAME, e.getMessage(), e);
        }
    }

    /**
     * 만료된 쿠폰을 EXPIRED 상태로 변경합니다.
     *
     * @return 변경된 쿠폰 수
     */
    @Transactional
    public int expireExpiredCoupons() {
        OffsetDateTime now = OffsetDateTime.now();

        // 만료일이 지난 ISSUED 상태의 쿠폰 조회
        List<MemberCoupon> expiredCoupons = memberCouponRepository.findIssuedWithExpiredDate(now);
        int count = expiredCoupons.size();

        for (MemberCoupon coupon : expiredCoupons) {
            coupon.markExpired();
            memberCouponRepository.save(coupon);
        }

        if (count > 0) {
            log.info("[{}] Expired {} coupons", JOB_NAME, count);
        }

        return count;
    }
}
