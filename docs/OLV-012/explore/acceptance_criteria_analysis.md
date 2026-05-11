# OLV-012 Explore: Acceptance Criteria Analysis

## AC.1: 인증/인가 (401/403)

- 모든 엔드포인트는 401 without auth — SecurityConfig가 자동 처리
- 회원이 다른 회원의 주소지 접근 시 403 — 명시적 검증 필요

**검증 방법**:
1. JWT 없이 호출 → 401
2. 회원 A의 토큰으로 회원 B의 주소지 접근 → 403

## AC.2: 기본 배송지 전환 트랜잭션

- `isDefault=true`인 새 주소지 추가 시 기존 default 배송지를 `false`로 변경
- 같은 트랜잭션에서 처리해야 함 (race condition 방지)

**구현 옵션**:
1. JPA `@Transactional` + UPDATE 문 명시 실행
2. DB partial unique index가 중복 방지하지만, 기존 default 해제는 애플리케이션 책임

## AC.3: PII 마스킹 (나중에 OLV-063)

- 현재 티켓에서는 소유 회원에게 전체 노출
- admin list view 마스킹은 OLV-063으로 연기

## AC.4: 소유권 경계 통합 테스트

- 회원 A가 회원 B의 주소지를 읽기/편집/삭제 시도 시 403
- 통합 테스트에서 두 회원 생성 후 상호 접근 시도

## 엔드포인트 요약

| Method | Path | Description | Auth | Ownership Check |
|--------|------|-------------|------|-----------------|
| GET | /api/me | 프로필 조회 (id, email, name, phone, grade) | USER | 자기 자신만 |
| PATCH | /api/me | name, phone 수정 | USER | 자기 자신만 |
| GET | /api/me/addresses | 주소지 목록 (default first) | USER | 자기 자신만 |
| POST | /api/me/addresses | 주소지 추가 | USER | 자기 자신만 |
| PATCH | /api/me/addresses/{id} | 주소지 수정 | USER | 소유권 검증 |
| DELETE | /api/me/addresses/{id} | 주소지 삭제 | USER | 소유권 검증 + 제약조건 |
