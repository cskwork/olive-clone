# Olive-clone × Symphony 멀티 에이전트 실행 가이드

이 디렉토리는 **올리브영형 헬스·뷰티 커머스 백엔드**를 [symphony-multi-agent]
오케스트레이터로 빌드하기 위한 워크스페이스입니다. 35개의 kanban ticket이
의존성 그래프로 묶여 있으며, Symphony가 차례로 깨워 Claude Code 에이전트가
**Todo → Explore → In Progress → Review → QA → Learn → Done** 7단계 파이프라인을
돌아 코드를 만들어 냅니다.

[symphony-multi-agent]: https://github.com/cskwork/symphony-multi-agent

## 디렉토리 구조

```
olive-clone/
├── Oliveyoung Like Commerce Backend Design.pdf  ← 원본 PRD (단일 진리원)
├── WORKFLOW.md                                  ← Symphony 오케스트레이터 설정
├── README-symphony.md                           ← 이 파일
├── kanban/                                      ← 35개 ticket (.md, YAML front matter)
│   ├── OLV-001.md  Bootstrap Spring Boot 프로젝트
│   ├── OLV-002.md  Postgres + Flyway
│   └── …
├── docs/                                        ← Symphony가 ticket별 산출물 저장
└── llm-wiki/                                    ← Explore 단계 부트 자료 (시드됨)
    ├── INDEX.md
    └── 00-architecture-overview.md … 99-failure-handling.md
```

## 사전 준비

```bash
# 1. symphony 설치 (이미 /opt/anaconda3/bin/symphony 에 설치됨)
which symphony

# 2. claude code CLI (현재 사용 중)
which claude

# 3. (선택) docker — OLV-002 부터 컨테이너가 필요
docker --version
```

## 실행

```bash
cd /Users/danny/Documents/PARA/Resource/olive-clone

# 0. 사전 점검 (포트 충돌, CLI 존재, WORKFLOW.md 파싱)
symphony doctor

# 1. 오케스트레이터 + TUI 켜기
symphony --tui WORKFLOW.md
#   - Jira 스타일 칸반 보드가 뜹니다.
#   - OLV-001 이 Todo 컬럼에 보이고, blocked_by 가 비어 있어 즉시 Explore 로
#     이동 → In Progress → ... 순서로 자동으로 깨어납니다.
#   - 다른 ticket 들은 blocker 가 Done 될 때까지 Todo 에 머무릅니다.

# 2. (선택) HTTP API 만 띄우고 백그라운드 실행
symphony --port 9991 WORKFLOW.md
# 이후 보드 상태 조회: curl http://localhost:9991/api/v1/state
```

## 의존성 그래프 (한눈에)

```
OLV-001 (bootstrap)
  └── OLV-002 (postgres + flyway)
        ├── OLV-010 (member schema) ──── OLV-011 (auth) ── OLV-012 (mypage)
        └── OLV-020 (product schema) ─── OLV-021 (brand/cat) ── OLV-022 (product CRUD) ── OLV-023 (public product)
              └── OLV-030 (inventory schema) ── OLV-031 (inventory svc)
              └── OLV-050 (promotion schema) ── OLV-051 (coupon) + OLV-052 (point)
              └── OLV-060 (order schema)
                    └── OLV-061 (order create) ── OLV-062 cancel + OLV-063 list/admin
                          └── OLV-070 (payment schema) ── OLV-071 (mock PG) ── OLV-072 (confirm)
                                └── OLV-073 (webhook), OLV-074 (refund)
                                └── OLV-080 (delivery)
                                └── OLV-090 (review)
                                └── OLV-110 (outbox + subscribers)
                                      └── OLV-120 (batch jobs)
  └── OLV-003 (redis/s3/opensearch) ── (used by OLV-031/040/100/...)
  └── OLV-004 (common) ── OLV-005 (security) ── (used by every API ticket)
                                                      └── OLV-100 (search index) ── OLV-101 (search api)
                                                      └── OLV-130 (observability) + OLV-131 (health)
                                                            └── OLV-140 (e2e) ── OLV-141 (load)
```

## 한 ticket 의 라이프사이클

`kanban/OLV-XXX.md` 의 `state` 필드가 곧 칸반 컬럼입니다. Symphony 가 ticket을
깨우면 Claude Code 가 ticket의 `## Description` 을 읽고:

1. **Triage (Todo)**: 정보가 충분한지 확인 → Explore 로 옮김.
2. **Explore**: `llm-wiki/` 와 PRD 를 읽고 `## Domain Brief` / `## Plan
   Candidates` / `## Recommendation` 작성 → In Progress.
3. **In Progress**: TDD 로 구현, `## Implementation` 추가 → Review.
4. **Review**: 자기 diff 검토 + curl 검증 → `## Review` 표 작성 → QA.
5. **QA**: 실제 테스트 / 실제 HTTP 호출 → `## QA Evidence` (실패 시 In
   Progress 로 되돌림).
6. **Learn**: `llm-wiki/` 에 새 사실 반영 → Done.
7. **Done**: `## As-Is → To-Be Report` 로 마무리.

각 단계 산출물은 `docs/OLV-XXX/<stage>/` 아래에 저장됩니다 — 워크스페이스가
호스트로 심볼릭 링크되어 있어 실시간으로 이 디렉토리에 쌓입니다.

## 자주 쓰는 운영 명령

```bash
# 보드 상태 빠르게 보기
ls kanban/ | xargs -I{} grep -H '^state:' kanban/{} | column -t -s: | sort -k2

# 특정 ticket 만 다시 큐잉 (state 를 Todo 로 되돌리기)
sed -i '' 's/^state:.*/state: Todo/' kanban/OLV-061.md

# Symphony 워크스페이스 정리
rm -rf ~/symphony_workspaces/OLV-*

# 보드 산출물 점검
ls -la docs/OLV-001/
```

## 작동 원리 요약

- **워크스페이스는 호스트의 심볼릭 링크**: `WORKFLOW.md` 의 `after_create`
  훅이 호스트 디렉토리(`olive-clone/`)의 모든 항목을
  `~/symphony_workspaces/<ID>/` 안에 심볼릭 링크합니다. 따라서 Claude 가
  `src/main/java/...` 에 파일을 만들면 **즉시 호스트 프로젝트에도 반영**됩니다.
- **순차 실행**: `max_concurrent_agents: 1` — 동일 코드베이스를 두 ticket 이
  동시에 만지지 못하도록 직렬화. (`run in sequence` 요구를 만족)
- **자동 의존성 처리**: ticket의 `blocked_by` 에 명시된 모든 ticket 이
  `Done` 이 되어야 해당 ticket 이 활성화됩니다. 따라서 그래프만 잘 짜면
  순서가 보장됩니다.
- **장애 복구**: QA 실패 → In Progress 로 자동 리윈드. Symphony 가
  다음 폴 틱에 다시 깨우며, retry attempt 카운터가 prompt 에 주입되어
  Claude 가 root cause 부터 다시 파악하도록 강제합니다.

## 다음 단계 (Phase 3, 옵션)

PRD §19.3 의 고도화 항목들은 별도 ticket 으로 추가하면 됩니다. 예시:

```bash
# 새 ticket 추가
cat > kanban/OLV-200.md <<'EOF'
---
id: OLV-200
title: 개인화 추천 — 사용자 행동 기반 협업 필터링
state: Todo
labels: [recommendation, ml]
blocked_by:
  - identifier: OLV-110
    state: Done
---

## Why
PRD §19.3 의 "개인화 추천" 항목 …
EOF
```

Symphony 가 다음 폴에서 자동으로 새 ticket 을 픽업합니다.
