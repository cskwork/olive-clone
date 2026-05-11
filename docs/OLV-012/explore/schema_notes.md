# OLV-012 Explore: Schema Notes

## member_addresses 테이블 (V2__member.sql)

```sql
CREATE TABLE member_addresses (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT       NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    recipient_name  VARCHAR(100) NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    zipcode         VARCHAR(10)  NOT NULL,
    address_main    VARCHAR(255) NOT NULL,
    address_detail  VARCHAR(255),
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 회원당 default 배송지 1건 강제 (partial unique index)
CREATE UNIQUE INDEX uniq_member_addresses_default_per_member
    ON member_addresses (member_id)
    WHERE is_default = TRUE;
```

## members 테이블

```sql
CREATE TABLE members (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    grade_id      BIGINT       NOT NULL REFERENCES member_grades(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

## member_grades 테이블

```sql
CREATE TABLE member_grades (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(50)    NOT NULL UNIQUE,
    discount_rate       DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    point_rate          DECIMAL(5, 2)  NOT NULL DEFAULT 0.00,
    benefit_description TEXT,
    sort_order          INTEGER        NOT NULL DEFAULT 0
);
```

## Key Invariants

1. **Partial Unique Index**: `uniq_member_addresses_default_per_member` — 회원당 is_default=TRUE인 배송지는 DB가 1건으로 강제
2. **CASCADE DELETE**: member 삭제 시 자동으로 주소지도 삭제
3. **phone nullable**: members 테이블의 phone은 NULL 허용 (member_addresses의 phone은 NOT NULL)
