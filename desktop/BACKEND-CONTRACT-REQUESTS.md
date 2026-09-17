# Backend Contract Requests

This file records A-class features that are blocked pending backend contract updates.
Each item maps to a specific checklist section.

> All items below are A-class. None may be marked deferred or postponed.
> Frontend implementation is ready to proceed immediately once the stable
> contract lands in `packages/api-client/src/generated/api.d.ts`.

## Status summary

| Item | Status | Note |
|------|--------|------|
| A2.1 provenance nav | Partial | Deep-link via `?block=` added; full nav needs `sourceId`/`sourcePageId` in `KnowledgePointSourceResponse`. |
| A3.2 AI context | Blocked | `SendMessageRequest` only contains `content`; context chips require optional context ID fields. |
| A8 Notes | Blocked | No `/notes` endpoints in `api.d.ts`. UI not implemented. |

---

## A8 Notes CRUD (A8.1 + A8.2 + A8.3)

**Checklist:** A8.1, A8.2, A8.3  
**Current gap:** No Notes endpoints exist in `packages/api-client/src/generated/api.d.ts`. The frontend has no Notes routes or UI.

### Required contract

**Endpoints**

1. `GET /api/v1/spaces/{spaceId}/notes` — list notes in a space
   - Query params: `page?`, `size?`, `keyword?`
   - Response: paginated list of Note summary views

2. `POST /api/v1/spaces/{spaceId}/notes` — create note
   - Request body: see `NoteCreateRequest` below
   - Response: `NoteView`

3. `GET /api/v1/spaces/{spaceId}/notes/{noteId}` — get note detail
   - Response: `NoteView`

4. `PUT /api/v1/spaces/{spaceId}/notes/{noteId}` — update note
   - Request body: see `NoteUpdateRequest` below
   - Response: `NoteView`

5. `DELETE /api/v1/spaces/{spaceId}/notes/{noteId}` — delete/archive note
   - Response: 204 No Content

**Schemas**

```ts
// Request: create note
NoteCreateRequest {
  title?: string
  content: string          // plain text or Markdown
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
  questionId?: number
}

// Request: update note (all fields optional; only changed fields need be present)
NoteUpdateRequest {
  title?: string
  content?: string
  sourceId?: number | null
  sourcePageId?: number | null
  contentBlockId?: number | null
  knowledgePointId?: number | null
  questionId?: number | null
}

// Response: note detail / list item
NoteView {
  id: number
  spaceId: number
  title?: string
  content: string
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
  questionId?: number
  createdAt: string
  updatedAt: string
}
```

**Frontend readiness**
- Routes reserved in `router.tsx` under `/spaces/:spaceId/notes` and `/spaces/:spaceId/notes/:noteId` (currently absent).
- UI modules will be created in `desktop/src/renderer/src/features/notes/`.
- Notes list/create/edit/delete wired through `@aistudy/api-client`.

---

## A2.1 KnowledgePoint → Source provenance navigation

**Checklist:** A2.1  
**Current gap:** `KnowledgePointSourceResponse` (returned by `GET /knowledge-points/{id}/sources`) only contains `contentBlockId`. The frontend can deep-link to Source with `?block=` but cannot navigate to the exact Source page or confirm ownership.

### Required contract

**Schema change**

```ts
KnowledgePointSourceResponse {
  id: number
  knowledgePointId: number
  contentBlockId: number
  sourceId: number            // ADD — required for Source navigation
  sourcePageId: number        // ADD — required for page-level deep-link
  relationType?: string
  createdAt?: string
}
```

**Frontend usage**
- `KnowledgePointDetailPage.tsx` reads `sourceId`, `sourcePageId`, `contentBlockId` from each provenance link.
- Navigate to `/spaces/:spaceId/sources/:sourceId?page=<sourcePageId>&block=<contentBlockId>`.
- If `sourcePageId` is absent/invalid, fallback to `/spaces/:spaceId/sources?block=<contentBlockId>` (workbench-level block highlight).

**Current fallback** (already implemented, pending backend):
- When `sourceId` is absent, the UI still renders a Source workbench deep-link using `?block=` only.

---

## A3.2 Source / Knowledge → Ask AI context

**Checklist:** A3.2  
**Current gap:** `SendMessageRequest` only contains `content?: string`. The AI tutor cannot receive context identifiers, so it cannot answer questions scoped to a specific Source page, ContentBlock, or KnowledgePoint.

### Required contract

**Schema change**

```ts
SendMessageRequest {
  content: string
  // ADD — all optional context identifiers
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
}
```

**Frontend usage**
- `AiChatPage.tsx` will send `sourceId`, `sourcePageId`, `contentBlockId`, `knowledgePointId` alongside `content` when the user opens AI from a Source/Knowledge context.
- A UI chip will display the active context and allow removal.
- The backend should use these IDs to scope retrieval/context injection; the frontend will not send full text or large payloads.

---

## A3.3 AI reference/citation stable IDs

**Checklist:** A3.3  
**Current gap:** AI response references (`references[]` on assistant messages) use `type` + `id` + `title` + `snippet`. The frontend can navigate to Source / KnowledgePoint / Question when `id` is present. However, for Source references the backend should also provide `sourcePageId` so the deep-link can jump to the exact page.

### Required contract

```ts
AiReference {
  type: 'SOURCE' | 'KNOWLEDGE_POINT' | 'QUESTION' | 'CONTENT_BLOCK'
  id: number
  title?: string
  snippet?: string
  // ADD for SOURCE / CONTENT_BLOCK references
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
}
```

**Frontend usage**
- `AiChatPage.tsx` maps `SOURCE` → `/spaces/:spaceId/sources/:sourceId?page=<sourcePageId>&block=<contentBlockId>`
- `KNOWLEDGE_POINT` → `/spaces/:spaceId/knowledge/:id`
- `QUESTION` → `/spaces/:spaceId/questions/:id`
- `CONTENT_BLOCK` → same as SOURCE deep-link

---

## A4.1 Question authoring — DELETE/ARCHIVE endpoint (informational)

**Checklist:** A4.4  
**Current gap:** The Question endpoints support list/get/create/publish. If the final product requires users to delete or archive questions, the frontend needs a corresponding mutation.

**Request:** If question deletion/archiving is a product requirement, add:
- `DELETE /api/v1/spaces/{spaceId}/questions/{questionId}` or
- `POST /api/v1/spaces/{spaceId}/questions/{questionId}/archive`

The frontend `QuestionDetailPage.tsx` is ready to accept an additional `delete` mutation once the endpoint is stable.

---

## Resolution order

1. A8 Notes — highest priority (entire feature blocked).
2. A2.1 provenance — unblock full Source/Page/Block navigation.
3. A3.2 AI context — unblock context-aware AI Tutor.
4. A3.3 AI reference IDs — polish citation deep-links (can follow A3.2).

Once the above contracts are reflected in `packages/api-client/src/generated/api.d.ts` and typecheck passes, the frontend will implement the remaining UI in a single follow-up pass.
