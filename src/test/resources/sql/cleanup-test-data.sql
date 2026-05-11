-- 테스트 데이터 정리
-- 이 파일은 @Sql 어노테이션을 통해 각 테스트 메서드 실행 후에 실행됩니다.

TRUNCATE TABLE payment_transactions, payments,
                  outbox_events, order_status_histories,
                  orders, inventory_reservations, inventory_histories, inventories,
                  member_addresses, members
RESTART IDENTITY CASCADE;
