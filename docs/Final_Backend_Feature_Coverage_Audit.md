# Final Backend Feature Coverage Audit (§13)

> Evidence-based status against `AIStudy_Final_Backend_Remaining_Development_Checklist.md`.
> Codes: **AC** = ALREADY COMPLETE · **CR** = COMPLETED IN THIS ROUND · **OOS** = PERMANENTLY OUT OF SCOPE · **P** = still PARTIAL (blocks FEATURE FROZEN declaration)

## Platform / Auth
| Item | Status | Evidence |
|---|---|---|
| Login/refresh/logout/me | AC | `AuthController` |
| Admin user create/reset/status/roles | AC | `AdminUserController` `@PreAuthorize(ADMIN)` |
| LearningSpace lifecycle | AC | V031 + space services |
| Archived write policy | AC | space/source lifecycle services |

## Source / Ingestion
| Item | Status | Evidence |
|---|---|---|
| Upload allowlist webp/docx | CR | `SourceAssetService.ALLOWED_MIME_BY_EXTENSION` |
| RAW authorized read | CR | `GET .../assets/{assetId}/content` |
| TXT/MD/PDF text | AC | extraction services |
| PNG/JPEG/WebP magic + OCR | CR | `ImageContentExtractionService` + `OcrEngine` |
| PDF OCR fallback | CR | `PdfContentExtractionService` sparse-page OCR |
| DOCX main chain | AC/CR | `DocxContentParser` dispatch from ingestion |
| ZIP safety + idempotent entries | AC/CR | `ZipArchiveInspector` + `extractZipEntries` |
| Folder sync backend identity | CR | JWT-only FolderImportController + DELETE |
| Page order typed + sourceId SQL | CR | `ReorderPagesRequest` + `updateOrderByIdSpaceSource` |
| IngestionIssue producers | CR | `recordIfLowConfidence` |
| Outline path ownership + cycle | CR | `SourceOutlineNodeService` typed DTO |
| Content block admin edit | CR | Admin block edit API |
| Review/publish lifecycle | AC | source/revision status services |
| Extraction revision ownership | CR | V053 columns + stamp + current pointer |
| Stage retry / job claim / recovery | CR | `claimQueued` + recovery runner |
| Version compare real revision | CR | `SourceVersionCompareService` rewrite |
| sha256 duplicate policy | P | post-upload detect only; not pre-commit importAgain contract |

## Knowledge / Note / Search
| Item | Status | Evidence |
|---|---|---|
| Category/KP lifecycle | AC | domain services |
| KP relation | AC/CR | relation CRUD + client wrappers |
| Note CRUD + JWT owner | CR | Note* controllers no userId |
| Note↔KP / Note↔Source | CR | services + controllers |
| QuestionSource SourceReference | CR | `SourceReferenceService` |
| Unified Search + NOTE | CR | SearchMapper NOTE branches |
| Chinese full-text | P | V054 ngram indexes exist; ranking still LIKE-fallback primary |

## Learning Engine
| Item | Status | Evidence |
|---|---|---|
| Frozen 7 question types payload | CR | validatePayloadShape MATCHING/ORDERING/FILL_BLANK/… |
| Practice filter auto selection | CR | category/kp/difficulty/type/seed |
| Practice history KP/category | CR | listMine filters |
| WrongQuestion states | AC | wrong_question services |
| Review four triggers | AC | WRONG_ANSWER/EXAM_DIAGNOSIS/LOW_MASTERY/MANUAL |
| SM-2 previousInterval + single apply | CR | `sm2Advance(..., previousInterval)` + no double apply |
| Review→Mastery | CR | evidence SQL + recomputeForReviewQuestion |
| AI variant workflow | P | not fully closed this freeze push |

## Exam / Stats
| Item | Status | Evidence |
|---|---|---|
| Exam lifecycle/paper snapshot | AC | exam domain |
| Blueprint engine | P | entity/service exist; rule engine not fully audited this round |
| Auto-submit | AC | scheduler + finalize |
| Subjective ADMIN grade + recompute | CR | controller 403 + gradeAnswer recompute |
| PARTIALLY_GRADED | CR | V055 + attempt update |
| Exam→Mastery/Diagnosis downstream on grade | CR | recomputeResultAfterGrade |
| Statistics | AC | ExamStatistics* |

## Mastery / Plan
| Item | Status | Evidence |
|---|---|---|
| Practice+Exam+Review evidence in score | CR | MasteryService recompute |
| algorithmVersion | CR | calibration active version |
| StudyPlan LEARN/PRACTICE/REVIEW/EXAM | AC | StudyPlanService.collectCandidates |
| Calibration runtime keys | CR | SystemConfig registry + mastery.confidenceFullSamples |

## AI
| Item | Status | Evidence |
|---|---|---|
| Settings/BYOK/Tutor/explain/coach | AC | AI-001~009 + prior smoke |
| Generation job API + claim | CR | AiGenerationJobController + claim |
| knowledgePointId not revisionId | CR | V056 + worker |
| Safe job error sanitizer | CR | `sanitizeSafeMessage` |
| Usage record on every new purpose | P | old paths covered; generation purposes not exhaustively instrumented |

## Admin / Contract / Ops
| Item | Status | Evidence |
|---|---|---|
| Admin spaces/knowledge/question/exam/ai jobs/bulk | AC | admin package + PreAuthorize |
| Admin Source/Ingestion/OCR governance | CR | AdminSourceIngestionController |
| SystemConfig typed registry | CR | server descriptors + PUT value-only |
| Shared client wrappers (AI + new APIs) | CR | packages/api-client client.ts |
| CORS PUT/DELETE | CR | ServerCorsConfig |
| Stable error registry | P | still many ResponseStatusException reasons without machine codes |
| Metrics/health Docker OCR runtime | P | OCR runtime in image not fully standardized |

## Out of scope (never this freeze)
Android/photo flows, public multi-tenant spaces, social/payments, realtime collab, object-store mandatory, chunk upload, full A/V parse, .doc, Mastery event sourcing, Redis/Kafka/ES, autonomous agents, frontend direct DB/AI.

---

## Blocking remaining (P) for FEATURE FROZEN
1. sha256 pre-upload/importAgain contract complete
2. Chinese FULLTEXT ranking wired (not just indexes)
3. AI variant workflow end-to-end
4. Exam blueprint rule-engine audit completion
5. AI usage on all generation purposes
6. Machine-readable error registry
7. Docker OCR runtime parity
8. §14 tests + §15 runtime + §16 docs

**Conclusion:** Feature code substantially landed; **NOT yet eligible to write FEATURE FROZEN** until §14–§16 close and remaining P items are either finished or explicitly accepted as non-blocking by the product owner.
