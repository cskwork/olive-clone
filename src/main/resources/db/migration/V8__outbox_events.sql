-- OLV-100 Outbox events table (wiki/96-eventing §Decision log).
-- 본 파일은 applied 후 절대 수정 금지 (Hard rule). 후속 변경은 V9+ 로 추가.

-- ---------------------------------------------------------------------------
-- outbox_events — 도메인 변경 → 비동기 fan-out 큐 (PRD §12, §18.3).
-- ---------------------------------------------------------------------------
-- 본 티켓(OLV-100)은 검색 인덱스 동기화(`PRODUCT_INDEX_SYNC`)에서 처음 사용.
-- 향후 OrderCreated/PaymentApproved 등도 같은 테이블을 공유한다(wiki §12.2).
--
-- 트랜잭션 경계: 도메인 쓰기 트랜잭션이 outbox row를 같은 트랜잭션 안에
-- insert(status='PENDING'). 별도 워커가 SELECT FOR UPDATE SKIP LOCKED로
-- 픽업 → 외부 시스템 호출 → 성공 시 'DONE', 실패 시 attempt_count+1,
-- 5회 도달 시 dlq=true (관리자 수동 재처리).

CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload_json    TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    dlq             BOOLEAN      NOT NULL DEFAULT FALSE,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_check CHECK (
        status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED')
    ),
    CONSTRAINT outbox_events_attempt_count_non_negative CHECK (attempt_count >= 0)
);

COMMENT ON TABLE  outbox_events IS 'Async outbox queue (wiki §96, PRD §12, §18.3).';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Source aggregate (e.g., PRODUCT).';
COMMENT ON COLUMN outbox_events.aggregate_id   IS 'Source aggregate primary key (e.g., products.id).';
COMMENT ON COLUMN outbox_events.event_type     IS 'Event class identifier (e.g., PRODUCT_INDEX_SYNC).';
COMMENT ON COLUMN outbox_events.payload_json   IS 'JSON payload — kept TEXT for portability.';
COMMENT ON COLUMN outbox_events.status         IS 'PENDING | IN_PROGRESS | DONE | FAILED (transient retry).';
COMMENT ON COLUMN outbox_events.attempt_count  IS 'Retry counter. dlq=true once it reaches 5.';
COMMENT ON COLUMN outbox_events.dlq            IS 'Dead-letter flag for manual recovery.';

-- 드레이너 스캔: status='PENDING' AND dlq=false ORDER BY id ASC LIMIT 100.
CREATE INDEX idx_outbox_events_drain
    ON outbox_events (status, dlq, id)
    WHERE status = 'PENDING' AND dlq = FALSE;

-- DLQ 어드민 조회 용도.
CREATE INDEX idx_outbox_events_dlq
    ON outbox_events (dlq, created_at)
    WHERE dlq = TRUE;
