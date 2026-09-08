# Current Task

## Objective

AIProject 后端业务块（AUTORUN-CONTINUE-014_016）：BUSINESS-014 Mastery 收口（证据契约修复 + hook + 测试）、BUSINESS-015 结构化 ExamDiagnosis（V020）、BUSINESS-016 StudyPlan/StudyTask（V021）、008~016 cross-phase 静态审计。已全部 COMPLETE + RUNTIME VERIFIED。

## Current Phase

**BUSINESS-008-016 FINAL BACKEND CLOSEOUT**

## Final Status Board

- BUSINESS-008 COMPLETE — RUNTIME VERIFIED
- BUSINESS-009 COMPLETE — RUNTIME VERIFIED
- BUSINESS-010 COMPLETE — RUNTIME VERIFIED
- BUSINESS-011 COMPLETE — RUNTIME VERIFIED
- BUSINESS-012 COMPLETE — RUNTIME VERIFIED
- BUSINESS-013 COMPLETE — RUNTIME VERIFIED
- BUSINESS-014 COMPLETE — RUNTIME VERIFIED
- BUSINESS-015 COMPLETE — RUNTIME VERIFIED
- BUSINESS-016 COMPLETE — RUNTIME VERIFIED
- LEGACY-SPIKE-002-TEST-CONTEXT-FIX — verified by full clean 442/442

## Final acceptance evidence

### Focused runtime（用户真实输出）
```
Tests run: 168   Failures: 0   Errors: 0   Skipped: 0   BUILD SUCCESS
```

### Full clean（用户真实输出）
```
Tests run: 442   Failures: 0   Errors: 0   Skipped: 0   BUILD SUCCESS
```

### Live OpenAPI（用户真实输出）
```
GET http://localhost:8080/v3/api-docs
REACHABLE
```

### Shared API generation（用户真实输出）
```
npm run api:generate
openapi-typescript 7.13.0
src/generated/openapi.json -> src/generated/api.d.ts
DONE
```

### Shared client typecheck（用户真实输出）
```
npm run typecheck
tsc --noEmit
PASS
```

## Regression protection still in place

- Mastery LocalDateTime conversion: `asLocalDateTime` / `latestEvidenceAt` in MasteryService
- ReviewTaskMapper: `AND rt.status = 'PENDING'` x2
- WrongReview Jackson JSON helper
- Exam deadline test helper (untimed = null)
- OpenAPI simple schema refs: `schemas/...$...` residual 0
- exam_diagnosis_item cleanup: 14 places via parent id cascade
- ResourceLock: 20 flyway-it classes uniform lock name
- StudyPlan JsonPath filtered collection assertions (`contains(...)` on `.taskType/.reason`)

## Production / business logic change status

BUSINESS-008~016 production code:
- No new business logic changes in final closeout.
- Final validation only.

Legacy SPIKE-002:
- Test-only context narrowing already completed.

## Docs / generated artifacts

- docs/current-task.md: updated to FINAL BACKEND CLOSEOUT
- docs/development-log.md: appended full acceptance chain + SPIKE-002 final verification
- docs/development-plan.md: BUSINESS-008~016 marked COMPLETE / VERIFIED
- packages/api-client/src/generated/openapi.json: live-generated contract artifact
- packages/api-client/src/generated/api.d.ts: live-generated TS client artifact

## Next Actions

1. Git review / commit (user manual):
   - docs/current-task.md
   - docs/development-log.md
   - docs/development-plan.md
   - server/src/... (business test/production code from BUSINESS-008~016)
   - packages/api-client/src/generated/* (if repo policy accepts generated contract files)
2. Next business block not started: Formal Auth / Admin / AI / PDF/Search

## Resume Instructions

Repository + docs authoritative。禁止 git add/commit/push/reset/restore；禁止伪造 runtime PASS。下一块业务未获用户指令不得开始。
