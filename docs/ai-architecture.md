# AI Backend Architecture

> Status: **IMPLEMENTATION COMPLETE / VERIFICATION DEFERRED**
>
> Scope: AI-001 ~ AI-004 first backend slice only.

## Goal

Learning-context-aware tutor whose context comes from the user's actual
LearningSpace. This is **not** a generic chatbot.

## Main flow

```text
user message
→ LearningSpace authorization
→ persist USER message (short DB tx)
→ assemble learning context via SearchService
→ build tutor prompt (system policy + history + context + user turn)
→ AiProvider.chat() OUTSIDE DB transaction
→ persist ASSISTANT message (short DB tx)
→ return conversation/message response
```

## Layers

| Layer | Package | Responsibility |
|---|---|---|
| API | `ai.controller` | thin REST, auth subject from JWT |
| Orchestration | `ai.service` | conversation lifecycle + tutor turn |
| Context | `ai.context` | bounded SearchService retrieval |
| Prompt | `ai.prompt` | system policy + untrusted context delimiting |
| Provider | `ai.provider` | `AiProvider` port + OpenAI-compatible adapter |
| Persistence | `ai.entity` / `ai.mapper` | `ai_conversation`, `ai_message` |
| Config | `ai.config` | `aistudy.ai.*` typed properties |

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
