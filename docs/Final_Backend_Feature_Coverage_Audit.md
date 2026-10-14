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
| IngestionIssue producers | CR | `recordIfLowConfidence` + `recordOrderingIssues` (OCR / EXTRACTION_PARTIAL / ORDER_LOW_CONFIDENCE / OUTLINE_LOW_CONFIDENCE behind `aistudy.ingestion.review.*`) |
| Outline path ownership + cycle | CR | `SourceOutlineNodeService` typed DTO |
| Content block admin edit | CR | Admin block edit API |
| Review/publish lifecycle | AC | source/revision status services |
| Extraction revision ownership | CR | V053 columns + stamp + current pointer |
| Stage retry / job claim / recovery | CR | `claimQueued` + recovery runner |
| Version compare real revision | CR | `SourceVersionCompareService` rewrite |
| sha256 duplicate policy | CR | `GET .../assets/duplicates` dry-run precheck keyed by (space, sha256) + `DuplicatePolicy` SKIP/IMPORT_AGAIN with `REUSED_EXISTING` outcome (`SourceAssetService.precheckDuplicate`, F-13) |

## Knowledge / Note / Search
| Item | Status | Evidence |
|---|---|---|
| Category/KP lifecycle | AC | domain services |
| KP relation | AC/CR | relation CRUD + client wrappers |
| Note CRUD + JWT owner | CR | Note* controllers no userId |
| Note↔KP / Note↔Source | CR | services + controllers |
| QuestionSource SourceReference | CR | `SourceReferenceService` |
| Unified Search + NOTE | CR | SearchMapper NOTE branches |
| Chinese full-text | CR | `MATCH(...) AGAINST` tiers wired in `SearchMapper.xml` behind `useFulltext`/`fulltextSelects`, LIKE kept as the punctuation/short-word fallback (F-03); `SearchMapperSqlTest`, `SearchFulltextQueryTest`, `SearchIntegrationTest` |

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
| AI variant workflow | CR | `save=true` → typed 201 `SavedVariantResponse` (F-16), server-verified stem/options/answer consistency before any write, invalid model output persists nothing (F-17), both paths booked in the usage ledger (F-23) — `AiVariantContractTest` |

## Exam / Stats
| Item | Status | Evidence |
|---|---|---|
| Exam lifecycle/paper snapshot | AC | exam domain |
| Blueprint engine | CR | distributions enforced as quotas (scarcest-cell-first fill, exact total, `scoreByDifficulty` honoured) with 409 `EXAM_BLUEPRINT_UNSATISFIED` instead of a silent short paper, and 400 for contradictory quotas at create (F-24) — unit + real-MySQL ITs |
| Auto-submit | AC | scheduler + finalize |
| Subjective ADMIN grade + recompute | CR | E-04: attempts resolve by space for governance (`gradeAnswerAsAdmin`), the grade writes `is_correct` with the score, regrade is allowed with `previous_score/previous_feedback` kept, objective slots refuse a hand score, and finalize refuses 409 `EXAM_GRADING_INCOMPLETE` instead of asserting GRADED — `ExamAdminGradingIntegrationTest` (13) |
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
| Usage record on every new purpose | CR | `AiUsageRecordService.recordProviderCall` is the single entry point and every purpose books both outcomes — `CONTENT_STRUCTURE`, `KNOWLEDGE_EXTRACTION`, `QUESTION_GENERATION`, `VARIANT`, `GRADING_SUGGESTION` (F-23) |
| AI-assisted grading (E-07) | CR | `AiGradingSuggestionService` + `POST /admin/exams/{examId}/attempts/{attemptId}/answers/{answerId}/grading-suggestion`: read-only by construction, holds no write path, `finalScore` always false, out-of-range scores clamped and flagged, unparseable response is a 409 rather than a silent zero |

## Admin / Contract / Ops
| Item | Status | Evidence |
|---|---|---|
| Admin spaces/knowledge/question/exam/ai jobs/bulk | AC | admin package + PreAuthorize |
| Admin Source/Ingestion/OCR governance | CR | AdminSourceIngestionController |
| SystemConfig typed registry | CR | server descriptors + PUT value-only |
| Shared client wrappers (AI + new APIs) | CR | packages/api-client client.ts |
| CORS PUT/DELETE | CR | ServerCorsConfig |
| Stable error registry | CR | every business branch answers ProblemDetail with a machine code; `ErrorCodeRatchetTest` runs on an **empty** baseline and rejects both an untyped 409 and any `ApiException(..., ApiErrorCodes.CONFLICT, ...)`, and `conflictCodesArePublishedInTheContract` proves the whole vocabulary is in `/v3/api-docs` (F-30) |
| Metrics/health Docker OCR runtime | P | OCR runtime in image not fully standardized |

## Out of scope (never this freeze)
Android/photo flows, public multi-tenant spaces, social/payments, realtime collab, object-store mandatory, chunk upload, full A/V parse, .doc, Mastery event sourcing, Redis/Kafka/ES, autonomous agents, frontend direct DB/AI.

---

## Blocking remaining (P) for FEATURE FROZEN
Re-measured against the working tree on 2026-10-14, after the E-04/E-07 round. Six of the eight items listed here
before are now closed in code and covered by tests (duplicate policy, FULLTEXT ranking, AI variant, blueprint quotas,
usage ledger, error registry), and the §14 test gate is green: `mvn test` on real MySQL = **1073 tests / 0 failures /
0 errors / 2 skipped**.

What actually still blocks the freeze is verification that needs artifacts or access this workspace does not have,
not missing implementation:

1. **OCR runtime parity (T-17 / T-21)** — `deploy/ocr` (sidecar image + `ocr_sidecar.py` + pinned paddlepaddle 2.6.2 /
   paddleocr 2.7.3), the `engine = none | process | http` wiring, the timeout/byte-cap/confidence-failure semantics and
   `docs/operations-ocr-docker.md` are all in the tree, and the app fails predictably with OCR disabled. Not yet done:
   building the 3–5 GB image and running a real Chinese JPG/WebP/scanned-PDF page through it on Windows and in Docker.
2. **T-16 real-textbook E2E** — needs the 《数据库系统工程师教程》 ZIP; no such fixture exists anywhere in the repo, so
   the chain can only be exercised on synthetic fixtures until someone supplies it.
3. **T-18 StepFun / BYOK with a live key** — the code paths (per-user runtime config over env, AES-GCM at rest,
   test-connection, generation jobs, usage rows) are implemented and tested against a mocked provider; the real
   network contract needs a key that is not in this workspace.
4. **Frontend-side contract work** — `client.ts` wrapper reconciliation (C-02) and a desktop/admin smoke against the
   live document (C-04), including the new ADMIN grading surface, are the frontend's lane.
5. **T-22** — the user owns commits/push; nothing in this round is pushed.

**Conclusion:** every `[F]` release blocker in the 2026-10-13 audit is fixed in code, and the `[P]` closures are
covered by the green suite on real MySQL. This document must not be read as proof of FEATURE FROZEN: items 1–3 are
gates that have not been *run*, and the audit's own rule applies — unexecuted verification is not a PASS. Until those
gates are executed with their required artifacts, the accurate status remains:

```text
FINAL BACKEND IMPLEMENTATION PRESENT AND TESTED
CONFIRMED FUNCTIONAL GAPS CLOSED
RUNTIME/EXTERNAL GATES T-16 T-17 T-18 T-21 NOT EXECUTED
```
