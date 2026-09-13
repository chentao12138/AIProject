# Current Task

## Objective
AI V1 runtime closeout (no new business features).

## Current Phase
**AI V1 BACKEND RELEASE CANDIDATE READY** (pending USER commit)

## Status Board

- AI-005~009 automated verification = PASS
- Full clean regression = PASS (639 / 0 / 0 / 2 skipped)
- StepFun real smoke = PASS
- Real boot + Actuator = PASS
- Live OpenAPI = PASS
- api-client regenerate + typecheck = PASS
- Docker smoke = PASS
- Secret / git hygiene = PASS

## StepFun real smoke (2026-09-13)

- Provider: openai-compatible → `https://api.stepfun.com/v1`
- Model: `step-3.5-flash`
- Runtime settings/secret rows: 0 (env path used; no BYOK delete required)
- `POST /api/v1/settings/ai/test-connection`: success=true, latencyMs≈2605
- Tutor `sendMessage`: USER+ASSISTANT persisted, model/token usage recorded
- Study Coach: 200 with contextReferences
- Archive: ACTIVE → ARCHIVED
- API key not present in logs, settings GET, or tutor response JSON

### Smoke-discovered production fix

`test-connection` hardcoded `max_tokens=8`. Reasoning models such as
`step-3.5-flash` spend budget on hidden reasoning before visible content,
returning empty `choices[0].message.content` → HTTP 502
`AI_PROVIDER_RESPONSE_INVALID`. Raised budget to 64.

## Remaining limitations (accepted for V1)

- Windows symlink tests still skipped (2)
- Tutor context search may return empty for long CJK sentences (query
  derivation + search matching); Coach demonstrated non-empty refs
- No Review-answer explanation endpoint (no independent Review answer entity)
- SSRF hostname allowlist deferred (documented V1 Desktop trust assumption)
- Real StepFun smoke uses env API key; encrypted BYOK path covered by tests

## Next Actions (USER)

1. Review + `git add` / commit / push (developer must not Git write)
2. Then FE integration / product acceptance / next-version scope
