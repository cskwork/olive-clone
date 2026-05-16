#!/bin/bash
#
# k6 부하 테스트 실행 래퍼 스크립트
#
# 사용법:
#   ./runload.sh product-list    # 상품 목록 부하 테스트
#   ./runload.sh order-create    # 주문 생성 부하 테스트
#   ./runload.sh order-create stock-out  # 재고 고갈 테스트
#
# 환경변수:
#   BASE_URL - 타겟 API URL (기본: http://localhost:8080)
#   STOCK_OUT_PRODUCT_OPTION_ID - 재고 고갈 테스트용 상품 옵션 ID (기본: 1)
#   OUTPUT_DIR - 결과 저장 디렉토리 (기본: docs/OLV-141/qa)
#

set -e

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 설정
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_ROOT/docs/OLV-141/qa}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

# 결과 파일 경로
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# 사용법 출력
usage() {
    echo "사용법: $0 <scenario> [mode]"
    echo ""
    echo "시나리오:"
    echo "  product-list    상품 목록 부하 테스트 (50 VU, 2분)"
    echo "  order-create    주문 생성 부하 테스트 (20 VU, 2분)"
    echo ""
    echo "모드 (order-create 전용):"
    echo "  stock-out       재고 고갈 테스트 (50 VU, 30초)"
    echo ""
    echo "환경변수:"
    echo "  BASE_URL                    타겟 API URL (기본: http://localhost:8080)"
    echo "  STOCK_OUT_PRODUCT_OPTION_ID 재고 고갈 테스트용 상품 옵션 ID (기본: 1)"
    echo "  OUTPUT_DIR                  결과 저장 디렉토리"
    echo ""
    echo "예시:"
    echo "  $0 product-list"
    echo "  $0 order-create stock-out"
    echo "  BASE_URL=http://localhost:8080 $0 order-create"
    exit 1
}

# 인자 확인
if [ $# -lt 1 ]; then
    usage
fi

SCENARIO="$1"
MODE="$2"

# 시나리오별 스크립트 및 설정
case "$SCENARIO" in
    product-list)
        SCRIPT="product-list.js"
        OUTPUT_PREFIX="k6-product-list"
        ;;
    order-create)
        SCRIPT="order-create.js"
        OUTPUT_PREFIX="k6-order-create"

        if [ "$MODE" = "stock-out" ]; then
            export STOCK_OUT_MODE=true
            OUTPUT_PREFIX="${OUTPUT_PREFIX}-stock-out"
        fi
        ;;
    *)
        echo -e "${RED}오류: 알 수 없는 시나리오 '$SCENARIO'${NC}"
        usage
        ;;
esac

# 출력 디렉토리 생성
mkdir -p "$OUTPUT_DIR"

# k6 설치 확인
if ! command -v k6 &> /dev/null; then
    echo -e "${RED}오류: k6가 설치되지 않았습니다.${NC}"
    echo "설치 방법: https://k6.io/docs/getting-started/installation/"
    exit 1
fi

# 타겟 서비스 확인
echo -e "${YELLOW}타겟 서비스 확인 중: $BASE_URL${NC}"
if ! curl -sSf "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    echo -e "${YELLOW}경고: 타겟 서비스에 연결할 수 없습니다 ($BASE_URL)${NC}"
    echo -e "${YELLOW}docker-compose up -d 로 서비스를 먼저 시작하세요.${NC}"
    read -p "계속 진행하시겠습니까? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# k6 실행
echo -e "${GREEN}k6 부하 테스트 시작: $SCENARIO${NC}"
echo "  스크립트: $SCRIPT"
echo "  타겟: $BASE_URL"
echo "  결과: $OUTPUT_DIR/${OUTPUT_PREFIX}-${TIMESTAMP}.json"
echo ""

OUTPUT_FILE="$OUTPUT_DIR/${OUTPUT_PREFIX}-${TIMESTAMP}.json"
SUMMARY_FILE="$OUTPUT_DIR/${OUTPUT_PREFIX}-${TIMESTAMP}-summary.txt"

cd "$SCRIPT_DIR"

# k6 실행 (JSON 결과 저장)
k6 run \
    --out json="$OUTPUT_FILE" \
    --summary-export="$SUMMARY_FILE" \
    "$SCRIPT" 2>&1 | tee -a "$SUMMARY_FILE"

# 결과 요약
EXIT_CODE=${PIPESTATUS[0]}
echo ""
echo "================================================================"
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}테스트 완료${NC}"
else
    echo -e "${RED}테스트 실패 (종료 코드: $EXIT_CODE)${NC}"
fi
echo "================================================================"
echo "결과 파일:"
echo "  - $OUTPUT_FILE"
echo "  - $SUMMARY_FILE"

# 재고 고갈 테스트 결과 확인
if [ "$MODE" = "stock-out" ]; then
    echo ""
    echo -e "${YELLOW}재고 고갈 테스트 결과 확인:${NC}"

    # 성공 주문 수 확인 (JSON 결과에서 추출)
    SUCCESS_COUNT=$(grep -o '"success":[0-9]*' "$SUMMARY_FILE" | grep -o '[0-9]*' | head -1)

    if [ -n "$SUCCESS_COUNT" ]; then
        echo "  성공 주문 수: $SUCCESS_COUNT"

        if [ "$SUCCESS_COUNT" = "10" ]; then
            echo -e "  ${GREEN}✅ PASS: 정확히 10건 성공${NC}"
        else
            echo -e "  ${RED}❌ FAIL: 기대 10건, 실제 $SUCCESS_COUNT건${NC}"
        fi
    fi
fi

echo ""
echo "Grafana 대시보드에서 부하 스파이크를 확인하세요:"
echo "  http://localhost:3000/d/commerce-backend/commerce-backend-observability"

exit $EXIT_CODE
