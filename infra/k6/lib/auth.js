/**
 * k6 인증 헬퍼 모듈
 *
 * - 로그인 후 JWT 토큰을 캐싱
 * - Bearer 토큰 헤더 생성
 */

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, HEADERS } from './config.js';

/**
 * 로그인 요청
 *
 * @param {string} email
 * @param {string} password
 * @returns {string} access token
 */
export function login(email, password) {
    const payload = JSON.stringify({
        email: email,
        password: password
    });

    const params = {
        headers: HEADERS,
    };

    const res = http.post(`${BASE_URL}/api/auth/login`, payload, params);

    check(res, {
        'login successful': (r) => r.status === 200,
    });

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        return body.data.accessToken;
    }

    return null;
}

/**
 * Bearer 토큰 헤더 생성
 *
 * @param {string} token
 * @returns {object} headers with Authorization
 */
export function withAuth(token) {
    return {
        ...HEADERS,
        'Authorization': `Bearer ${token}`,
    };
}
