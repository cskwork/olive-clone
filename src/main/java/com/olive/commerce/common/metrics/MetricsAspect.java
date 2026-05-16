package com.olive.commerce.common.metrics;

import com.olive.commerce.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 메트릭 수집 Aspect (OLV-130).
 *
 * <p>도메인 서비스의 핵심 메서드를 인터셉트하여 메트릭을 기록한다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class MetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(MetricsAspect.class);

    private final CommerceMetrics metrics;

    @Around("execution(* com.olive.commerce.inventory.InventoryService.reserve(..))")
    public Object aroundInventoryReserve(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            // 예약 실패 시 옵션 ID 추출 (두 번째 파라미터의 첫 번째 항목)
            Object[] args = joinPoint.getArgs();
            if (args.length >= 2 && args[1] instanceof Iterable<?> items) {
                for (Object item : items) {
                    if (item instanceof InventoryService.ReserveItem reserveItem) {
                        metrics.inventoryReservationFailure(reserveItem.optionId());
                        break;
                    }
                }
            }
            throw e;
        }
    }

    @Around("execution(* com.olive.commerce.inventory.InventoryService.reserveWithRedisLock(..))")
    public Object aroundInventoryReserveWithRedisLock(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            recordReservationFailure(joinPoint.getArgs());
            throw e;
        }
    }

    @Around("execution(* com.olive.commerce.inventory.InventoryService.reserveWithDbLock(..))")
    public Object aroundInventoryReserveWithDbLock(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            recordReservationFailure(joinPoint.getArgs());
            throw e;
        }
    }

    private void recordReservationFailure(Object[] args) {
        if (args.length >= 2 && args[1] instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item instanceof InventoryService.ReserveItem reserveItem) {
                    metrics.inventoryReservationFailure(reserveItem.optionId());
                    break;
                }
            }
        }
    }
}
