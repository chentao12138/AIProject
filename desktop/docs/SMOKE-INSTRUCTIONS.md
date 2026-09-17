# AIStudy Desktop Smoke Instructions

This document captures the remaining manual smoke steps required before
declaring `FRONTEND V1 FUNCTIONALLY COMPLETE`.

## A10 Installed-app Auth Smoke

### Prerequisites

- Windows x64 machine
- Backend running at `http://localhost:8080` with a valid MySQL schema
- Valid test username/password

### Steps

1. Build installer:
   ```powershell
   cd D:\AIProject-frontend\desktop
   npm run dist:win
   ```
2. Install `dist/AIStudy Setup.exe` (double-click, accept defaults).
3. Launch AIStudy from Start Menu.
4. Verify Electron boot:
   - Window title = `AIStudy`
   - No startup crash / uncaught exception
5. Login:
   - Enter test username/password
   - Click Login
   - Verify redirect to `/spaces`
6. Close app (window X or system tray close).
7. Reopen app from Start Menu.
8. Verify session restore:
   - App returns to `/spaces` automatically (refresh token restore + `/auth/me`)
   - No manual re-login required
9. Logout:
   - Click User / Logout in AppShell
   - Verify redirect to `/login`
10. Reopen app:
    - App shows `/login` (anonymous)
11. Shutdown / uninstall:
    - `Add or Remove Programs` → AIStudy → Uninstall
    - Verify `%APPDATA%\AIStudy` (or equivalent Electron userData) is removed or acceptable

### Pass criteria

- Login → close → reopen → authenticated
- Logout → reopen → anonymous
- No token leakage to localStorage / logs / disk in renderer

---

## A11.3 Critical E2E Smoke

### Prerequisites

- Backend running at `http://localhost:8080`
- Valid test user with at least one LearningSpace
- `E2eBackendHarness` (or equivalent test JWT mechanism) available if backend requires seeded data

### Core user journeys

#### 1. Login

- Open app → `/login`
- Submit credentials
- Expect: redirect to `/spaces`

#### 2. Create/Open Space

- Create a new LearningSpace (name only)
- Expect: auto-redirect to `/spaces/{id}/sources`
- Re-open the space from `/spaces`
- Expect: same space data loads

#### 3. Source upload + ingestion

- Open Sources page
- Upload a TXT file
- Wait for ingestion SUCCEEDED
- Verify content area shows extracted text
- Upload a PDF (multi-page)
- Verify page navigation + text extraction

#### 4. Knowledge provenance

- Open Knowledge page
- Create a KnowledgePoint linked to a source
- Open point detail
- Click provenance link
- Expect: Source workbench opens with target page/block highlighted

#### 5. Question

- Open Questions page
- Create a Single-Choice question
- Verify list shows the new question
- Open detail → Publish
- Verify status badge changes to PUBLISHED

#### 6. Practice

- Open Practice page
- Start a session (5 questions)
- Answer questions + submit
- Finish session
- Verify result view shows score / answered / correct

#### 7. Exam

- Open Exams page
- Create an exam
- Open attempt
- Answer questions + save
- Submit exam
- Verify result view shows score / review items

#### 8. Search

- Open Search page
- Enter a query that matches a source, knowledge point, and question
- Verify results render with deep-links
- Click each result type → correct target page opens

#### 9. Notes (BLOCKED)

- No Notes endpoints in current backend contract
- Skip until A8 contract lands in `packages/api-client`

#### 10. AI Settings/Chat

- Open AI Settings
- Configure provider + API key
- Save → verify key not returned by GET
- Open AI Tutor
- Send a message
- Verify response renders + references (if any) are clickable

#### 11. Logout/restore

- Logout from AppShell
- Reopen app
- Verify anonymous state (`/login`)

### Pass criteria

- All enabled journeys above complete without runtime errors
- 401/403/404/5xx/network show normalized error states with retry
- Back/forward/refresh restore correct state
- No Node API / direct fetch / token leakage observed

---

## A11.4 Production Security Scan

### Static scan targets

- `desktop/src/renderer/src/**/*.tsx`:
  - No `localStorage` / `sessionStorage`
  - No `fetch(` / `XMLHttpRequest` / `axios`
  - No `require('electron')` / `ipcRenderer` / `process.env`
  - No `dangerouslySetInnerHTML`
- `desktop/src/main/**`:
  - `contextIsolation=true`, `nodeIntegration=false`, `sandbox=true`, `webSecurity=true`
  - No `webSecurity=false`
- `desktop/src/preload/**`:
  - No `ipcRenderer` leak outside `contextBridge`

### Command

```powershell
cd D:\AIProject-frontend\desktop
npm run lint
npx tsc --noEmit -p tsconfig.node.json && npx tsc --noEmit -p tsconfig.web.json
npm run test:run
npm run build
```

### Pass criteria

- Lint: 0 errors / 0 warnings
- Typecheck: exit 0
- Tests: all pass
- Build: exit 0
- Manual source grep confirms no forbidden patterns in renderer production code

---

## A11.5 Final Documentation Checklist

- [x] README matches implementation
- [x] `docs/frontend-current-task.md` reflects current state
- [x] `docs/frontend-development-log.md` appended with current checkpoint
- [x] `docs/ROUTE_MAP.md` lists all routes
- [x] `docs/FINAL-FEATURE-MATRIX.md` lists implemented / blocked / out-of-scope
- [x] `BACKEND-CONTRACT-REQUESTS.md` lists A8/A2.1/A3.2/A3.3 contracts
- [x] Packaging instructions in README
- [x] Smoke instructions in this file
