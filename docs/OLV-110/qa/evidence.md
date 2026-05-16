# OLV-110 QA Evidence

## 실행 명령어

```bash
cd /Users/danny/Documents/PARA/Resource/olive-clone
./gradlew cleanTest test --tests "*OutboxEventIntegrationTest" --rerun-tasks
```

## 결과

```
BUILD SUCCESSFUL in 13s
6 actionable tasks: 6 executed

<testsuite name="com.olive.commerce.common.event.OutboxEventIntegrationTest"
    tests="7" skipped="0" failures="0" errors="0">
  <testcase name="AC1: 결제 승인 후 아웃박스 이벤트 생성 및 1초 내 DONE 상태로 변경" ... PASSED/>
  <testcase name="AC2: PaymentApprovedEvent 구독자들이 정확히 한 번씩 실행됨" ... PASSED/>
  <testcase name="AC3: 드레이너 중지 시 이벤트 소실 없음 - 재시작 시 처리됨" ... PASSED/>
  <testcase name="AC4: 실패한 이벤트는 attempt_count 증가, 5회 실패 시 DEAD 상태" ... PASSED/>
  <testcase name="OrderCanceledEvent: 알림 발송" ... PASSED/>
  <testcase name="다중 아웃박스 이벤트 순차 처리" ... PASSED/>
  <testcase name="DeliveryCompletedEvent: 리뷰 작성 가능 마크 (단순화)" ... PASSED/>
</testsuite>
```

## AC 매핑

| AC | 설명 | 테스트 | 결과 |
|----|------|--------|------|
| AC1 | 결제 승인 후 outbox row 생성, 1초 내 DONE | `paymentApproved_createsOutboxEvent_andProcessesWithinOneSecond` | ✓ |
| AC2 | PaymentApproved 구독자 3개가 정확히 한 번씩 실행 | `paymentApproved_allSubscribersFireExactlyOnce` | ✓ |
| AC3 | 드레이너 중지 시 이벤트 소실 없음, 재시작 시 처리 | `drainerStop_doesNotLoseEvents_restartProcessesThem` | ✓ |
| AC4 | 실패 시 attempt_count 증가, 5회 실패 시 DEAD(FAILED+dlq) | `poisonedEvent_increasesAttemptCount_fiveFailuresMovesToDead` | ✓ |

## 로그 예시

```
INFO  c.o.c.c.n.NotificationService - Order confirmed notification: memberId=1, orderNo=TEST-ORD-xxx, amount=53000
DEBUG c.o.c.c.a.SalesAggregator - Sale recorded: date=2026-05-13, orderId=1, amount=53000
DEBUG c.o.c.p.PointService - Flipped pending points: orderId=1, memberId=1, amount=2650
DEBUG c.o.c.r.ReviewEligibilityCache - Review eligibility marked: orderId=1
```

## 판정

**판정**: **PASS** - 모든 AC가 충족되었습니다.
