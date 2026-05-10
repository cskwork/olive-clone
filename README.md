# commerce-backend

Olive Young 스타일의 health & beauty 커머스 백엔드 — Spring Boot 3.x · Java 21 ·
모듈러 모놀리식. 전체 설계는 `Oliveyoung Like Commerce Backend Design.pdf` (PRD)
와 `llm-wiki/`에 정리되어 있다.

## 사전 준비

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 21 | `brew install openjdk@21` (macOS) — 빌드 toolchain은 Gradle이 자동으로 받아온다. |
| Gradle | wrapper 사용 | `./gradlew`만 사용한다. 호스트에 별도 설치 불필요. |

`JAVA_HOME`이 JDK 21을 가리키도록 한다 (예: macOS Homebrew 기준
`export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`).

## 자주 쓰는 명령

```bash
# 애플리케이션 실행 (포트 8080)
./gradlew bootRun

# 단위 테스트
./gradlew test

# 단일 jar 빌드 (build/libs/commerce-backend-0.1.0.jar)
./gradlew build
```

기동 후 헬스 체크:

```bash
curl -s http://localhost:8080/actuator/health
# → {"status":"UP"}
```

이번 부트스트랩 단계에서는 데이터베이스가 의도적으로 비활성화되어 있다
(`spring.autoconfigure.exclude=DataSourceAutoConfiguration`). PostgreSQL과
Flyway는 OLV-002 티켓에서 도입된다.

## 패키지 구조

```
com.olive.commerce
├── member       회원·주소·등급·JWT
├── product      상품·옵션·이미지·브랜드·카테고리
├── search       OpenSearch 색인 동기화
├── cart         장바구니 (익명·회원 병합)
├── order        주문 상태 머신 + 상품 스냅샷
├── payment      PG 연동 + 멱등 웹훅
├── inventory    옵션 단위 재고 + 분산 락
├── promotion    쿠폰·포인트
├── delivery     배송 + 캐리어 비동기
├── review       구매 검증 리뷰
├── admin        백오피스
└── common       공통 인프라 (config, error, util)
```

도메인 간 통신은 `ApplicationEventPublisher` + outbox 테이블만 사용한다
(직접 repository 호출 금지 — `llm-wiki/00-architecture-overview.md`).

## 작업 흐름

이 저장소의 모든 변경은 7단계 Symphony 파이프라인을 따른다:
**Todo → Explore → In Progress → Review → QA → Learn → Done**.
자세한 규칙은 [`WORKFLOW.md`](./WORKFLOW.md)에 있다.
