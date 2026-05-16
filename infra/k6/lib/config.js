/**
 * k6 공통 설정 모듈
 *
 * - BASE_URL: 타겟 API 기본 URL (환경변수 또는 기본값)
 * - HEADERS: API 요청에 사용할 공통 헤더
 */

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const HEADERS = {
    'Content-Type': 'application/json',
};

/**
 * 상품 목록 정렬 옵션
 */
export const SORT_OPTIONS = ['LATEST', 'POPULAR', 'PRICE_ASC', 'PRICE_DESC', 'RATING'];

/**
 * 랜덤 정렬 옵션 선택
 */
export function getRandomSort() {
    return SORT_OPTIONS[Math.floor(Math.random() * SORT_OPTIONS.length)];
}
