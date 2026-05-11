-- 테스트 데이터 설정: Payment Confirm API 테스트용
-- 이 파일은 @Sql 어노테이션을 통해 각 테스트 메서드 실행 전에 로드됩니다.

-- Clean up (member_grades는 Flyway seed data이므로 제외)
TRUNCATE TABLE payment_transactions, payments,
                  outbox_events, order_status_histories,
                  orders, inventory_reservations, inventory_histories, inventories,
                  member_addresses, members
RESTART IDENTITY CASCADE;

-- 회원 생성 (BRONZE 등급 ID = 1)
INSERT INTO members (id, email, password_hash, name, phone, status, grade_id)
VALUES (1, 'payment-test@example.com', '$2a$12$test', '결제테스트', '01012345678', 'ACTIVE', 1);

-- 배송지 생성
INSERT INTO member_addresses (id, member_id, recipient_name, phone, zipcode, address_main, address_detail, is_default)
VALUES (1, 1, '홍길동', '01012345678', '12345', '서울시 강남구', '101호', true);

-- 주문 생성 (PAYMENT_PENDING)
INSERT INTO orders (id, member_id, delivery_address_id, status,
    total_product_amount, discount_amount, point_used_amount,
    delivery_fee, final_payment_amount, order_no)
VALUES (1, 1, 1, 'PAYMENT_PENDING', 50000, 0, 0, 3000, 35000, 'ORD202605100001');

-- 결제 생성 (REQUESTED)
INSERT INTO payments (id, order_id, method, status, requested_amount, idempotency_key)
VALUES (1, 1, 'CARD', 'REQUESTED', 35000, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11');
