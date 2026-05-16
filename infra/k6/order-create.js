/**
 * k6 주문 생성 부하 테스트
 *
 * 시나리오:
 * - 20 VU가 2분 동안 회원가입 → 로그인 → 장바구니 → 주문생성 → 결제승인 플로우 실행
 * - 마지막 30초(stock-out tail): 50 VU가 재고 10개인 SKU에 대해 주문 시도
 *   → 정확히 10건은 성공(200), 나머지는 422 INSUFFICIENT_INVENTORY, 500 에러는 0건이어야 함
 *
 * 실행:
 *   k6 run order-create.js
 *
 * 환경변수:
 *   BASE_URL=http://localhost:8080 k6 run order-create.js
 *   STOCK_OUT_MODE=true k6 run order-create.js  # 재고 고갈 모드
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { BASE_URL } from './lib/config.js';
import { login, withAuth } from './lib/auth.js';

// 테스트 데이터 (재고 고갈 테스트용)
const STOCK_OUT_PRODUCT_OPTION_ID = __ENV.STOCK_OUT_PRODUCT_OPTION_ID || 1;
const STOCK_OUT_MODE = __ENV.STOCK_OUT_MODE === 'true';

// 테스트 사용자 데이터 공유 (VU 간 순차적 접근)
const testUsers = new SharedArray('testUsers', function () {
    // 100명의 테스트 사용자 생성
    const users = [];
    for (let i = 1; i <= 100; i++) {
        users.push({
            email: `k6test${i}@example.com`,
            password: 'Test1234!',
            name: `K6TestUser${i}`,
            phone: `010${String(i).padStart(8, '0')}`,
        });
    }
    return users;
});

// 테스트 설정
export const options = {
    // 일반 부하: 20 VU, 2분
    stages: STOCK_OUT_MODE
        ? [
            // 재고 고갈 테스트: 50 VU, 30초
            { duration: '10s', target: 50 },
            { duration: '20s', target: 50 },
            { duration: '5s', target: 0 },
          ]
        : [
            { duration: '20s', target: 5 },
            { duration: '60s', target: 20 },
            { duration: '30s', target: 20 },
            { duration: '10s', target: 0 },
          ],

    // 임계값
    thresholds: {
        // p95 지연 시간 < 1s (주문 플로우는 더 복잡하므로 널널하게)
        'http_req_duration': ['p(95)<1000'],
        // 오류율 < 5% (재고 부족 등 비즈니스 오류 포함)
        'http_req_failed': ['rate<0.05'],
    },
};

// 통계 카운터
const orderStats = {
    success: 0,
    insufficientStock: 0,
    otherErrors: 0,
};

/**
 * 회원가입
 */
function signup(user) {
    const payload = JSON.stringify({
        email: user.email,
        password: user.password,
        name: user.name,
        phone: user.phone,
    });

    const res = http.post(
        `${BASE_URL}/api/auth/signup`,
        payload,
        { headers: { 'Content-Type': 'application/json' } }
    );

    // 409 (이미 존재)는 성공으로 간주 (멱등성)
    return res.status === 201 || res.status === 409;
}

/**
 * 장바구니 추가
 */
function addToCart(token, productOptionId, quantity = 1) {
    const payload = JSON.stringify({
        productOptionId: productOptionId,
        quantity: quantity,
    });

    const res = http.post(
        `${BASE_URL}/api/cart/items`,
        payload,
        { headers: withAuth(token) }
    );

    return check(res, {
        'cart add success': (r) => r.status === 201 || r.status === 200,
    });
}

/**
 * 배송지 조회 (첫 번째 배송지 ID 반환)
 */
function getFirstAddressId(token) {
    const res = http.get(
        `${BASE_URL}/api/me/addresses`,
        { headers: withAuth(token) }
    );

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        if (body.data && body.data.length > 0) {
            return body.data[0].id;
        }
    }

    return null;
}

/**
 * 배송지 생성
 */
function createAddress(token) {
    const payload = JSON.stringify({
        recipientName: '테스트 수령인',
        phone: '01012345678',
        zipcode: '12345',
        addressMain: '서울시 강남구',
        addressDetail: '101호',
        isDefault: true,
    });

    const res = http.post(
        `${BASE_URL}/api/me/addresses`,
        payload,
        { headers: withAuth(token) }
    );

    if (res.status === 201) {
        const body = JSON.parse(res.body);
        return body.data.id;
    }

    return null;
}

/**
 * 주문 생성
 */
function createOrder(token, productOptionId, addressId) {
    const payload = JSON.stringify({
        items: [
            {
                productOptionId: productOptionId,
                quantity: 1,
            },
        ],
        deliveryAddressId: addressId,
    });

    const params = {
        headers: {
            ...withAuth(token),
            'Idempotency-Key': `k6-${Date.now()}-${__VU}`,
        },
    };

    const res = http.post(`${BASE_URL}/api/orders`, payload, params);

    if (res.status === 201) {
        const body = JSON.parse(res.body);
        return body.data;
    }

    return null;
}

/**
 * 결제 승인 (Mock PG)
 */
function confirmPayment(token, orderData) {
    const payload = JSON.stringify({
        orderNo: orderData.orderNo,
        paymentKey: `k6-mock-${Date.now()}-${__VU}`,
        amount: orderData.amount,
    });

    const params = {
        headers: {
            ...withAuth(token),
            'Idempotency-Key': `k6-payment-${Date.now()}-${__VU}`,
            'X-Mock-Pg-Behaviour': 'approve',
        },
    };

    const res = http.post(`${BASE_URL}/api/payments/confirm`, payload, params);

    return check(res, {
        'payment status': (r) => r.status === 200 || r.status === 422 || r.status === 409,
        'payment response has data': (r) => {
            if (r.status !== 200) return true; // 실패해도 OK
            try {
                const body = JSON.parse(r.body);
                return body.data !== undefined;
            } catch (e) {
                return false;
            }
        },
    });
}

/**
 * 주문 생성 플로우 (메인 시나리오)
 */
function orderFlow(user, productOptionId) {
    let token;

    // 1. 회원가입 (또는 로그인)
    if (!signup(user)) {
        console.log(`Signup failed for ${user.email}`);
        return;
    }

    // 2. 로그인
    token = login(user.email, user.password);
    if (!token) {
        console.log(`Login failed for ${user.email}`);
        return;
    }

    // 3. 장바구니 추가
    if (!addToCart(token, productOptionId)) {
        console.log(`Add to cart failed for ${user.email}`);
        return;
    }

    // 4. 배송지 확인 또는 생성
    let addressId = getFirstAddressId(token);
    if (!addressId) {
        addressId = createAddress(token);
    }
    if (!addressId) {
        console.log(`No address for ${user.email}`);
        return;
    }

    // 5. 주문 생성
    const orderData = createOrder(token, productOptionId, addressId);
    if (!orderData) {
        // 주문 생성 실패 (재고 부족 등)
        orderStats.otherErrors++;
        return;
    }

    // 6. 결제 승인
    if (confirmPayment(token, orderData)) {
        orderStats.success++;
    } else {
        orderStats.otherErrors++;
    }
}

/**
 * 재고 고갈 테스트 (stock-out tail)
 */
function stockOutFlow(user) {
    let token;

    if (!signup(user)) return;
    token = login(user.email, user.password);
    if (!token) return;

    if (!addToCart(token, STOCK_OUT_PRODUCT_OPTION_ID)) return;

    let addressId = getFirstAddressId(token);
    if (!addressId) {
        addressId = createAddress(token);
    }
    if (!addressId) return;

    const payload = JSON.stringify({
        items: [
            {
                productOptionId: STOCK_OUT_PRODUCT_OPTION_ID,
                quantity: 1,
            },
        ],
        deliveryAddressId: addressId,
    });

    const params = {
        headers: {
            ...withAuth(token),
            'Idempotency-Key': `k6-stockout-${Date.now()}-${__VU}`,
        },
    };

    const res = http.post(`${BASE_URL}/api/orders`, payload, params);

    if (res.status === 201) {
        const orderData = JSON.parse(res.body).data;

        const paymentPayload = JSON.stringify({
            orderNo: orderData.orderNo,
            paymentKey: `k6-stockout-${Date.now()}-${__VU}`,
            amount: orderData.amount,
        });

        const paymentParams = {
            headers: {
                ...withAuth(token),
                'Idempotency-Key': `k6-payment-${Date.now()}-${__VU}`,
                'X-Mock-Pg-Behaviour': 'approve',
            },
        };

        const paymentRes = http.post(
            `${BASE_URL}/api/payments/confirm`,
            paymentPayload,
            paymentParams
        );

        if (paymentRes.status === 200) {
            orderStats.success++;
        } else if (paymentRes.status === 422) {
            // INSUFFICIENT_INVENTORY
            const body = JSON.parse(paymentRes.body);
            if (body.code === 'INSUFFICIENT_INVENTORY') {
                orderStats.insufficientStock++;
            } else {
                orderStats.otherErrors++;
            }
        } else {
            orderStats.otherErrors++;
        }
    } else {
        // 주문 생성 실패
        const body = JSON.parse(res.body);
        if (body.code === 'INSUFFICIENT_INVENTORY') {
            orderStats.insufficientStock++;
        } else {
            orderStats.otherErrors++;
        }
    }
}

/**
 * 메인 시나리오 함수
 */
export default function () {
    // VU별 사용자 할당 (순차적 접근 보장)
    const userIndex = __VU % testUsers.length;
    const user = testUsers[userIndex];

    if (STOCK_OUT_MODE) {
        stockOutFlow(user);
    } else {
        // 일반 모드: 랜덤 상품 옵션 (1~10)
        const productOptionId = Math.floor(Math.random() * 10) + 1;
        orderFlow(user, productOptionId);
    }

    // 1~3초 랜덤 대기
    sleep(Math.random() * 2 + 1);
}

/**
 * 테스트 종료 시 요약
 */
export function handleSummary(data) {
    const summary = {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        'summary.json': JSON.stringify(data),
    };

    if (STOCK_OUT_MODE) {
        summary['stock-out-report.txt'] = stockOutSummary();
    }

    return summary;
}

/**
 * 텍스트 요약 헬퍼
 */
function textSummary(data, options) {
    const lines = [];

    lines.push('\n=== k6 주문 생성 부하 테스트 결과 ===');
    lines.push(`총 요청 수: ${data.metrics.http_reqs.values.count}`);
    lines.push(`최소 응답 시간: ${(data.metrics.http_req_duration.values.min * 0.001).toFixed(2)}ms`);
    lines.push(`평균 응답 시간: ${(data.metrics.http_req_duration.values.avg * 0.001).toFixed(2)}ms`);
    lines.push(`p95 응답 시간: ${(data.metrics.http_req_duration.values['p(95)'] * 0.001).toFixed(2)}ms`);
    lines.push(`최대 응답 시간: ${(data.metrics.http_req_duration.values.max * 0.001).toFixed(2)}ms`);
    lines.push(`실패 요청 수: ${data.metrics.http_req_failed.values.passes}`);
    lines.push(`실패율: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`);
    lines.push('========================================\n');

    return lines.join('\n');
}

/**
 * 재고 고갈 테스트 요약
 */
function stockOutSummary() {
    const lines = [];

    lines.push('\n=== 재고 고갈 테스트 결과 ===');
    lines.push(`성공 주문 수: ${orderStats.success}`);
    lines.push(`INSUFFICIENT_INVENTORY 오류 수: ${orderStats.insufficientStock}`);
    lines.push(`기타 오류 수: ${orderStats.otherErrors}`);
    lines.push('');

    // 검증: 성공 수 = 10, 나머지는 422, 500은 0
    if (orderStats.success === 10 && orderStats.otherErrors === 0) {
        lines.push('✅ PASS: 정확히 10건 성공, 나머지는 INSUFFICIENT_INVENTORY, 500 에러 없음');
    } else {
        lines.push('❌ FAIL:');
        if (orderStats.success !== 10) {
            lines.push(`  - 기대: 성공 10건, 실제: ${orderStats.success}건`);
        }
        if (orderStats.otherErrors > 0) {
            lines.push(`  - 기대: 500 에러 0건, 실제: ${orderStats.otherErrors}건`);
        }
    }

    lines.push('============================\n');

    return lines.join('\n');
}
