# AI Backend Architecture

> Status: **AI-001~AI-004 VERIFIED; AI-005~AI-008 IMPLEMENTATION COMPLETE / VERIFICATION DEFERRED**

## Goal

Learning-context-aware tutor + grounded provenance + learning-state personalization
+ post-submit explanation + read-only study coach. This is **not** a generic chatbot.

## Main flows

### Tutor chat (pre-submit)

```text
user message
→ LearningSpace authorization
→ persist USER message (short DB tx)
→ assemble learning context via SearchService
→ assemble bounded LEARNING STATE
→ build prompt (SYSTEM / STATE / CONTEXT / HISTORY / USER)
→ AiProvider.chat() OUTSIDE DB transaction
→ persist ASSISTANT message + exact references (short DB tx)
→ return conversation/message + references
```

### Post-submit explanation (AI-007)

```text
authorize space/user
→ load submitted practice/exam answer
→ require session/attempt SUBMITTED (else 409)
→ load snapshot stem + grading outcome + safe KP ids
→ optional Search context
→ AiProvider.chat() OUTSIDE DB transaction
→ return ExplanationResponse
```

Deterministic grading remains source of truth. AI never grades.

### Study coach (AI-008, read-only)

```text
authorize space
→ AiLearningStateService (mastery/wrong/diagnosis/plan)
→ optional Search context
→ AiProvider.chat()
→ StudyCoachResponse (advisory only; no mutations)
```

## Safety boundaries

| Path | Correctness access |
|---|---|
| Pre-submit tutor | Search-safe projection only |
| Post-submit explanation | Authorized submitted evidence + deterministic grade |
| Study coach | Learning state signals only; no answer payloads |

## Layers

| Layer | Package | Responsibility |
|---|---|---|
| API | `ai.controller` | thin REST |
| Orchestration | `ai.service` / `explain` / `coach` | tutor / explanation / coach |
| Learning state | `ai.learning` | bounded domain read models |
| Context | `ai.context` | SearchService retrieval |
| Prompt | `ai.prompt` | SYSTEM + STATE + CONTEXT delimiting |
| Provider | `ai.provider` | AiProvider port |
| Persistence | `ai.entity` / `ai.mapper` | messages + references |
| Config | `ai.config` | `aistudy.ai.*` |

## Out of scope (still)

- SSE / streaming
- Vector DB / embeddings
- Tool-calling / AI mutations of learning engine
- Real provider smoke (verification phase)


## Dependency direction

```text
core learning/search  →  AI orchestration
```

Search MUST NOT depend on AI. AI MUST NOT inject into core domain.

## Security boundaries

- Conversations scoped by `(spaceId, userSubject)`; subject from JWT only.
- Foreign space/conversation → 404 (anti-probing).
- Search-derived context preserves:
  - LearningSpace isolation
  - WrongQuestion user isolation
  - Question correctness hiding
- Client cannot inject SYSTEM role.
- Learning context is marked untrusted DATA in the prompt.
- No tool-calling / side effects.

## Transaction / network boundary

DB transactions never wrap provider HTTP calls.

1. authorize
2. persist USER (commit)
3. assemble context
4. provider call (no DB tx)
5. persist ASSISTANT (commit)

On provider failure: USER message may remain; no fabricated assistant reply.

## Defaults

| Setting | Default |
|---|---|
| AI enabled | false |
| max-search-results | 8 |
| max-context-chars | 12000 |
| max-user-message-chars | 8000 |
| max-history-messages | 20 |
| conversation page size | 20 |
| message page size | 50 |

## Environment variables

| Variable | Purpose |
|---|---|
| `AISTUDY_AI_ENABLED` | enable AI (default false) |
| `AISTUDY_AI_PROVIDER` | provider id |
| `AISTUDY_AI_BASE_URL` | OpenAI-compatible base URL |
| `AISTUDY_AI_API_KEY` | secret; env/secrets only |
| `AISTUDY_AI_MODEL` | model id |
| `AISTUDY_AI_TEMPERATURE` | sampling temperature |
| `AISTUDY_AI_MAX_OUTPUT_TOKENS` | max completion tokens |
| `AISTUDY_AI_CONNECT_TIMEOUT` | connect timeout |
| `AISTUDY_AI_READ_TIMEOUT` | read timeout |
| `AISTUDY_AI_CONTEXT_*` | context limits |

## Out of scope (this slice)

- SSE / WebSocket streaming
- Vector DB / embeddings / RAG infrastructure
- Tool-calling / agent actions
- OCR / web browsing
- Multi-agent architecture
