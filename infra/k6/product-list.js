/**
 * k6 상품 목록 부하 테스트
 *
 * 시나리오:
 * - 50 VU가 2분 동안 상품 목록 조회
 * - categoryId=1, 랜덤 정렬 옵션
 * - 목표: p95 < 300ms
 *
 * 실행:
 *   k6 run product-list.js
 *
 * 환경변수:
 *   BASE_URL=http://localhost:8080 k6 run product-list.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './lib/config.js';
import { getRandomSort } from './lib/config.js';

// 테스트 설정
export const options = {
    // 2분 동안 50 VU로 부하
    stages: [
        { duration: '30s', target: 10 },   // 램프업
        { duration: '60s', target: 50 },   // 최대 부하
        { duration: '30s', target: 50 },   // 유지
        { duration: '10s', target: 0 },    // 램프다운
    ],

    // 임계값 (PRD §16.3)
    thresholds: {
        // p95 지연 시간 < 300ms
        'http_req_duration': ['p(95)<300'],
        // 오류율 < 1%
        'http_req_failed': ['rate<0.01'],
        // 요청 성공률 > 99%
        'checks': ['rate>0.99'],
    },
};

const BASE_PARAMS = {
    headers: {
        'Accept': 'application/json',
    },
};

/**
 * 상품 목록 조회 시나리오
 */
export default function () {
    // 랜덤 정렬 옵션
    const sort = getRandomSort();

    // 카테고리 ID=1 (스킨케어)
    const url = `${BASE_URL}/api/products?categoryId=1&sort=${sort}`;

    const res = http.get(url, BASE_PARAMS);

    // 응답 검증
    check(res, {
        'status is 200': (r) => r.status === 200,
        'has products array': (r) => {
            if (r.status !== 200) return false;
            const body = JSON.parse(r.body);
            return Array.isArray(body.data);
        },
        'response time < 500ms': (r) => r.timings.duration < 500,
    });

    // 1~3초 랜덤 대기 (사용자 행동 시뮬레이션)
    sleep(Math.random() * 2 + 1);
}

/**
 * 테스트 종료 시 요약
 */
export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        'summary.json': JSON.stringify(data),
    };
}

/**
 * 텍스트 요약 헬퍼
 */
function textSummary(data, options) {
    const lines = [];

    lines.push('\n=== k6 부하 테스트 결과 요약 ===');
    lines.push(`총 요청 수: ${data.metrics.http_reqs.values.count}`);
    lines.push(`최소 응답 시간: ${(data.metrics.http_req_duration.values.min * 0.001).toFixed(2)}ms`);
    lines.push(`평균 응답 시간: ${(data.metrics.http_req_duration.values.avg * 0.001).toFixed(2)}ms`);
    lines.push(`p95 응답 시간: ${(data.metrics.http_req_duration.values['p(95)'] * 0.001).toFixed(2)}ms`);
    lines.push(`최대 응답 시간: ${(data.metrics.http_req_duration.values.max * 0.001).toFixed(2)}ms`);
    lines.push(`실패 요청 수: ${data.metrics.http_req_failed.values.passes}`);
    lines.push(`실패율: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`);
    lines.push('===============================\n');

    return lines.join('\n');
}
