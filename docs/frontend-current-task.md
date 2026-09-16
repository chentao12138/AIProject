# Frontend Current Task

## Objective

FE-AI-001-PRE-COMMIT-FINALIZE-01

## Baseline

- Branch: feat/fe-001 @ cd39169 + uncommitted AI work
- api-client: AI Settings + Tutor/Chat (9 wrapper methods)

## Feature Audit

| Item | Status |
|------|--------|
| AI Provider Settings UI | DONE |
| apiKeyConfigured display | DONE |
| Safe key replacement (omit unchanged) | DONE |
| Clear key after save | DONE |
| Test connection | DONE |
| Delete key (real DELETE API) | DONE |
| Provider-not-configured flow | DONE (warning + settings link) |
| Conversation list/create/archive | DONE |
| Message send/render | DONE |
| References rendering | DONE (text display; no stable sourceId in ContextReference) |
| Auth-expiry recovery | DONE (existing resetQueries boundary) |
| Streaming | NON-STREAMING (contract has no stream endpoint) |
| Context picker | BLOCKED BY CONTRACT (no context input in SendMessageRequest) |
| Reference navigation | BLOCKED BY CONTRACT (ContextReference has no sourceId/pageId) |

## Security Audit

- deleteAiApiKey() → real DELETE /settings/ai/api-key ✓
- No localStorage/sessionStorage for key ✓
- No renderer → StepFun direct request ✓
- api.stepfun.com only as display placeholder text ✓
- No key logging ✓

## Real Smoke Status

| Smoke | Status |
|-------|--------|
| Settings backend smoke | PENDING — no running backend |
| Provider connection smoke | PENDING — no safe test credential |
| Chat request smoke | PENDING — no safe test credential |
| Production app:// | PASS (Electron alive 12s) |

## Final Gates

- lint OK / typecheck OK / test:run **343/343**（28 files）/ build OK
- Coverage: Statements 83.06% / Branches 81.36% / Functions 77.35%
- Build: 589.54 kB

## Final Status

AI Provider Settings IMPLEMENTED
AI Tutor/Chat IMPLEMENTED
Provider secrets backend-owned
No frontend secret persistence
No renderer direct provider access
REAL PROVIDER SMOKE PENDING — NO SAFE TEST CREDENTIAL
WINDOWS GATES VERIFIED
AWAITING USER REVIEW AND COMMIT

## Deferred

- Real backend smoke (needs running backend + test credential)
- Formal Auth / Exam / Practice / Search / Notes
