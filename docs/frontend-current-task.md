# Frontend Current Task

## Objective

FE-001 + FE-001.5 + FE-002A + FE-002B + FE-AI-001 + FE-QUESTION/PRACTICE/EXAM/SEARCH final completion.

## Current branch

- `feat/fe-001` (clean, uncommitted frontend work committed in logical batches)
- `packages/api-client` up to date with current stable backend contract

## Implemented (A-class)

| Feature | Routes / Scope | Status |
|---------|----------------|--------|
| Spaces | `/spaces` | Implemented |
| Sources + ingestion | `/spaces/:spaceId/sources`, `/spaces/:spaceId/sources/:sourceId` | Implemented (TXT/MD/PDF/PNG/JPEG) |
| Knowledge | `/spaces/:spaceId/knowledge`, `/spaces/:spaceId/knowledge/:knowledgePointId` | Implemented |
| AI Tutor | `/spaces/:spaceId/ai`, `/spaces/:spaceId/ai/:conversationId` | Implemented |
| AI Settings | `/settings/ai` | Implemented |
| Questions | `/spaces/:spaceId/questions`, `/spaces/:spaceId/questions/new`, `/spaces/:spaceId/questions/:questionId` | Implemented (create + publish only; see Blocked for the update verb) |
| Practice | `/spaces/:spaceId/practice`, `/spaces/:spaceId/practice/:sessionId` | Implemented |
| Exams | `/spaces/:spaceId/exams`, `/spaces/:spaceId/exams/:examId`, `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | Implemented |
| Search | `/spaces/:spaceId/search` | Implemented |
| Auth | `/login` | Implemented |

## Blocked (A-class, pending backend contract)

| Feature | Blocker | Contract request |
|---------|---------|------------------|
| Notes (A8) | No `/notes` endpoints in `api.d.ts` | See `BACKEND-CONTRACT-REQUESTS.md` |
| AI context chips (A3.2) | `SendMessageRequest` only has `content` | See `BACKEND-CONTRACT-REQUESTS.md` |
| Full provenance nav (A2.1) | `KnowledgePointSourceResponse` missing `sourceId`/`sourcePageId` | See `BACKEND-CONTRACT-REQUESTS.md` |
| AI reference deep-links (A3.3) | `AiReference` missing `sourcePageId` for SOURCE/CONTENT_BLOCK | See `BACKEND-CONTRACT-REQUESTS.md` |
| Question edit (A4.5) | No `PUT`/`PATCH /questions/{questionId}`; a "save" could only create a duplicate, so the edit route was removed | See `BACKEND-CONTRACT-REQUESTS.md` |

## Out of scope (B-class)

See `AISTUDY-FRONTEND-FINAL-COMPLETION-MASTER-CHECKLIST.md` section 12.

## Security contract

- BrowserWindow: `contextIsolation=true`, `nodeIntegration=false`, `sandbox=true`, `webSecurity=true`
- Renderer: no direct fetch/axios/XHR; no localStorage/sessionStorage token; no Node APIs
- AI: renderer never contacts provider directly; keys backend-owned
- Preload: `contextBridge` only; no `ipcRenderer` leak — the exposed surface
  (vault save/load/clear + read-only `aistudyConfig.getApiBaseUrl`) is asserted
  by `src/preload/index.test.ts`
- Backend target resolved at runtime by main (ADR-051):
  `AISTUDY_API_BASE_URL` -> `<userData>/config.json` -> `VITE_API_BASE_URL` ->
  default; CSP `connect-src` and the renderer client share that one value
- Single-instance lock: no second process may race the refresh-token vault
- Packaged builds: DevTools off, default menu removed (ADR-037)

## Final gates (current)

- lint: PASS（含 feature 互不 import、src/shared 平台无关等边界规则）
- typecheck: PASS
- tests: 48 files / 449 pass
- build: PASS（typecheck + `api:check` 契约漂移门禁 + electron-vite build，renderer bundle ~698 kB + CSS）

## Remaining work

1. Backend stable contract for A8/A2.1/A3.2 → frontend wiring
2. A10 installed-app auth smoke (manual)
3. A11.3 critical E2E smoke (manual)
4. Final docs/smoke instructions below
