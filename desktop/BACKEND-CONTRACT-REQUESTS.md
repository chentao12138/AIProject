# Backend Contract Requests

This file records A-class features that are blocked pending backend contract updates.
Each item maps to a specific checklist section.

## Status summary

| Item | Status | Note |
|------|--------|------|
| A2.1 provenance nav | Partial | Deep-link via `?block=` added; full nav needs `sourceId`/`sourcePageId` in `KnowledgePointSourceResponse`. |
| A3.2 AI context | Blocked | `SendMessageRequest` only contains `content`; context chips require optional `sourceId`/`sourcePageId`/`contentBlockId`/`knowledgePointId`. |
| A8 Notes | Blocked | No `/notes` endpoints in `api.d.ts`. UI not implemented. |

---

## A2.1 KnowledgePoint → Source provenance navigation

**Checklist:** A2.1  
**Current gap:** `KnowledgePointSourceResponse` (returned by `GET /knowledge-points/{id}/sources`) only contains `contentBlockId`. To navigate from a KnowledgePoint provenance link directly to the Source reader at the correct page/block, the response must also include:
- `sourceId`
- `sourcePageId`

**Request:** Add `sourceId` and `sourcePageId` to `KnowledgePointSourceResponse`.

---

## A3.2 Source / Knowledge → Ask AI context

**Checklist:** A3.2  
**Current gap:** `SendMessageRequest` only contains `content?: string`. To allow the AI tutor to answer questions in the context of a specific source/knowledge point, the request body must support:
- `sourceId?: number`
- `sourcePageId?: number`
- `contentBlockId?: number`
- `knowledgePointId?: number`

**Request:** Extend `SendMessageRequest` with optional context identifier fields.

---

## A8.1 Notes CRUD

**Checklist:** A8.1, A8.2, A8.3  
**Current gap:** No Notes endpoints exist in `api.d.ts`. The following endpoints are required for the Notes feature:
- `GET /api/v1/spaces/{spaceId}/notes` — list notes
- `POST /api/v1/spaces/{spaceId}/notes` — create note
- `GET /api/v1/spaces/{spaceId}/notes/{noteId}` — get note detail
- `PUT /api/v1/spaces/{spaceId}/notes/{noteId}` — update note
- `DELETE /api/v1/spaces/{spaceId}/notes/{noteId}` — delete/archive note

**Request:** Add full Notes CRUD endpoints to the OpenAPI contract.

---

## A4.1 Question authoring — DELETE/ARCHIVE endpoint

**Checklist:** A4.4  
**Current gap:** The Question endpoints support list/get/create/publish. If the final product requires users to delete or archive questions, the following endpoint is needed:
- `DELETE /api/v1/spaces/{spaceId}/questions/{questionId}` or similar archive endpoint

**Request:** If question deletion/archiving is a product requirement, add the corresponding endpoint.
