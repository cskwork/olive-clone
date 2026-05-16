package com.olive.commerce.common.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 알림 서비스 (Mock 구현).
 * <p>실제 알림 발송 대신 로그 출력과 JSON 파일 저장으로 대체합니다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String LOG_DIR = "log/notifications";

    private final ObjectMapper objectMapper;
    private final Map<String, NotificationSpy> spy = new ConcurrentHashMap<>();

    public NotificationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 주문 완료 알림을 발송합니다.
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @param amount   결제 금액
     */
    public void sendOrderConfirmed(Long memberId, String orderNo, String amount) {
        String notificationId = UUID.randomUUID().toString();
        NotificationData data = new NotificationData(
                notificationId,
                "ORDER_CONFIRMED",
                memberId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                Map.of(
                        "orderNo", orderNo,
                        "amount", amount
                )
        );

        log.info("Order confirmed notification: memberId={}, orderNo={}, amount={}",
                memberId, orderNo, amount);
        saveToFile(data);
        spy.put("ORDER_CONFIRMED:" + orderNo, new NotificationSpy(notificationId, memberId, orderNo));
    }

    /**
     * 주문 취소 알림을 발송합니다.
     *
     * @param memberId 회원 ID
     * @param orderNo  주문 번호
     * @param reason   취소 사유
     */
    public void sendCancellation(Long memberId, String orderNo, String reason) {
        String notificationId = UUID.randomUUID().toString();
        NotificationData data = new NotificationData(
                notificationId,
                "ORDER_CANCELED",
                memberId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                Map.of(
                        "orderNo", orderNo,
                        "reason", reason != null ? reason : "사용자 요청"
                )
        );

        log.info("Order cancellation notification: memberId={}, orderNo={}, reason={}",
                memberId, orderNo, reason);
        saveToFile(data);
        spy.put("ORDER_CANCELED:" + orderNo, new NotificationSpy(notificationId, memberId, orderNo));
    }

    /**
     * 테스트용 스파이: 알림 발송 여부를 확인합니다.
     *
     * @param key 스파이 키 (예: "ORDER_CONFIRMED:ORD001")
     * @return 알림 발송 정보
     */
    public NotificationSpy getSpy(String key) {
        return spy.get(key);
    }

    /**
     * 테스트용 스파이: 모든 알림을 반환합니다.
     */
    public Map<String, NotificationSpy> getAllSpies() {
        return new ConcurrentHashMap<>(spy);
    }

    /**
     * 테스트용 스파이: 모든 데이터를 초기화합니다.
     */
    public void clearSpy() {
        spy.clear();
    }

    private void saveToFile(NotificationData data) {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String filename = String.format("%s_%s.json",
                    data.type().toLowerCase(),
                    data.sentAt().replaceAll("[:T]", "-").substring(0, 19));

            File file = new File(dir, filename);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(objectMapper.writeValueAsString(data));
            }
        } catch (IOException e) {
            log.warn("Failed to save notification to file: {}", data.notificationId(), e);
        }
    }

    /**
     * 알림 데이터 DTO.
     */
    public record NotificationData(
            String notificationId,
            String type,
            Long memberId,
            String sentAt,
            Map<String, Object> payload
    ) {}

    /**
     * 테스트용 스파이 데이터.
     */
    public record NotificationSpy(
            String notificationId,
            Long memberId,
            String orderNo
    ) {}
}
