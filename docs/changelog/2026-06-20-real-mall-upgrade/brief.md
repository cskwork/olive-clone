# Brief — 실제 쇼핑몰 수준 개선

## Goal
기존 올리브영형 H&B 커머스(`com.olive.commerce` Spring Boot 3.3 / Java 21 모듈러 모놀리스 + React/Vite SPA storefront)를 "실제로 운영 가능한 쇼핑몰" 수준으로 끌어올린다. 코드 구조 전면 개편 허용. 디자인 개선은 superdesign 활용 가능.

## Audience
- 최종 사용자: 모바일/웹에서 H&B 상품을 탐색→장바구니→주문→결제하는 일반 소비자.
- 운영자: 상품/주문/배송/프로모션을 관리하는 admin.
- 개발자: 유지보수/확장.

## Acceptance Criteria (machine-checkable 지향 — Explore/Plan에서 구체화)
1. `./gradlew build` 와 기존 테스트 스위트가 그린. 신규 기능은 테스트 동반.
2. 비회원 탐색 → 장바구니 담기 → 로그인/병합 → 주문 생성 → 결제(현 단계 mock 허용) → 주문완료 플로우가 SPA에서 end-to-end 동작.
3. "실제 쇼핑몰" 핵심 갭 중 합의된 항목이 닫힘 (Explore 후 확정; 후보: 결제 신뢰성, 인증 일관성, 입력검증, rate limit, 위시리스트, My Page, 검색 필터, 운영 하드닝, 디자인 완성도).
4. 보안 필수 점검 통과: 인증/인가 단일경로, 금액 재검증, 비밀정보 비하드코딩, 공개 API rate limit.
5. delivery-gate.sh 통과 (artifacts + 그린 verdict + Coverage 맵).

## Non-Goals (현 런 범위 밖 — 별도 합의 전까지)
- 실제 PG사(Toss 등) 라이브 계약/정산 연동 (mock→real adapter 구조까지는 가능, 라이브 키 연동은 제외).
- 실제 택배사 API 라이브 연동.
- K8s 프로덕션 배포/인프라 프로비저닝.
- 신용카드 원문 저장 등 PCI 범위 작업.

## Validation
LEGACY 모드 — 수요검증(GO/NO-GO) 게이트는 GREENFIELD 전용이라 N/A. 기존 제품 개선이므로 demand는 기정.
