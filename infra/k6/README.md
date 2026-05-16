# k6 부하 테스트

k6를 사용한 커머스 백엔드 부하 테스트 스크립트 모음입니다.

## 사전 요구사항

1. **k6 설치**
   ```bash
   # macOS (Homebrew)
   brew install k6

   # Linux
   sudo apt-get install k6

   # 또는 바이너리 다운로드
   # https://k6.io/docs/getting-started/installation/
   ```

2. **타겟 환경** (docker-compose 기반)
   ```bash
   # 로컬에서 서비스 시작
   docker-compose up -d postgres redis localstack opensearch

   # 애플리케이션 시작
   ./gradlew bootRun
   ```

3. **Prometheus + Grafana** (OLV-130 참조)
   ```bash
   docker-compose up -d prometheus grafana
   ```

## 스크립트

### product-list.js

상품 목록 조회 부하 테스트

- **VU**: 최대 50
- **지속 시간**: 2분 (램프업 30초 + 유지 90초 + 램프다운 10초)
- **API**: `GET /api/products?categoryId=1&sort=LATEST`
- **목표**: p95 < 300ms

### order-create.js

주문 생성 플로우 부하 테스트

- **VU**: 최대 20 (일반), 50 (재고 고갈 모드)
- **지속 시간**: 2분 (일반), 30초 (재고 고갈)
- **플로우**: 회원가입 → 로그인 → 장바구니 → 주문생성 → 결제승인
- **목표**: p95 < 1s, 오류율 < 5%

## 실행 방법

### runload.sh 래퍼 사용 (권장)

```bash
# 상품 목록 부하 테스트
./runload.sh product-list

# 주문 생성 부하 테스트
./runload.sh order-create

# 재고 고갈 테스트 (재고 10개인 SKU에 대해 50 VU 동시 주문)
./runload.sh order-create stock-out
```

### 직접 k6 실행

```bash
# 상품 목록
k6 run product-list.js

# 주문 생성
k6 run order-create.js

# 재고 고갈 모드
STOCK_OUT_MODE=true k6 run order-create.js
```

### 환경변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `BASE_URL` | 타겟 API URL | `http://localhost:8080` |
| `STOCK_OUT_MODE` | 재고 고갈 모드 활성화 | `false` |
| `STOCK_OUT_PRODUCT_OPTION_ID` | 재고 고갈 테스트용 상품 옵션 ID | `1` |

```bash
BASE_URL=http://localhost:8080 k6 run product-list.js
```

## 결과 해석

### Pass/Fall 기준

#### product-list.js
- **PASS**: p95 < 300ms, 오류율 < 1%
- **FAIL**: p95 ≥ 300ms 또는 오류율 ≥ 1%

#### order-create.js (일반 모드)
- **PASS**: p95 < 1s, 오류율 < 5%
- **FAIL**: p95 ≥ 1s 또는 오류율 ≥ 5%

#### order-create.js (재소 고갈 모드)
- **PASS**: 정확히 10건 성공(200), 나머지는 422 `INSUFFICIENT_INVENTORY`, 500 에러 0건
- **FAIL**: 성공 건수 ≠ 10 또는 500 에러 발생

### 결과 파일

`runload.sh` 실행 시 다음 파일들이 생성됩니다:

```
docs/OLV-141/qa/
├── k6-product-list-20260513_120000.json       # 원시 결과
├── k6-product-list-20260513_120000-summary.txt # 요약
├── k6-order-create-20260513_121500.json
├── k6-order-create-20260513_121500-summary.txt
├── k6-order-create-stock-out-20260513_123000.json
└── k6-order-create-stock-out-20260513_123000-summary.txt
```

### Grafana 대시보드

부하 테스트 중 메트릭은 Prometheus에 수집되며, Grafana 대시보드에서 확인할 수 있습니다:

- URL: http://localhost:3000/d/commerce-backend/commerce-backend-observability
- 확인할 지표:
  - `http_server_requests_seconds` - API 응답 시간
  - `commerce_orders_created_total` - 주문 생성 수
  - `commerce_payments_total` - 결제 시도 수
  - `hikaricp_connections_active` - DB 연결 풀 사용량

## Golden Signals 캡처

모든 스크립트는 4가지 Golden Signals을 캡처합니다:

1. **Latency** - `http_req_duration` 메트릭
2. **Traffic** - `http_reqs` 메트릭
3. **Errors** - `http_req_failed` 메트릭
4. **Saturation** - Prometheus/Grafana에서 확인 (DB 풀, CPU, 메모리)

## 재고 고갈 테스트 상세

재고 고갈 테스트는 동시성 제어가 올바르게 동작하는지 검증합니다:

**시나리오**:
- 재고 10개인 상품 옵션 ID에 대해
- 50 VU가 동시에 주문 시도
- 결과: 정확히 10건만 성공, 나머지는 422 `INSUFFICIENT_INVENTORY`

**기대 동작**:
```
성공: 10건 (200)
실패: 40건 (422 INSUFFICIENT_INVENTORY)
500 에러: 0건
```

**실패 시 원인**:
- 재고 선점(reserve) 로직 버그
- Redis 분산 락 미작동
- DB 트랜잭션 격리 문제

## 참고

- PRD §16.3 - 성능 기준 및 임계값
- `llm-wiki/60-order-domain.md` - 주문 생성 파이프라인
- `llm-wiki/30-inventory-domain.md` - 재고 동시성 제어
- `llm-wiki/03-infra-baseline.md` - Prometheus/Grafana 설정
