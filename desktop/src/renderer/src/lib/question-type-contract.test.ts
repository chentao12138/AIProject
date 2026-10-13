/**
 * Questions contract guard (G02 / G04 / G09 / C09 / C-Paging).
 *
 * `backend-vocabulary-contract.test.ts` pins the type NAMES to the Java
 * constants. This file pins what the renderer may put on the wire and, just as
 * importantly, what it is NOT allowed to invent:
 *
 *  - every field `QuestionForm` writes into the create body must exist on
 *    `CreateQuestionRequest`;
 *  - the fields ORDERING / MATCHING would need (`correctOrder`, `matches`,
 *    `orderingAnswer`, `matchingAnswer`, any pair structure) must stay absent
 *    — and while they are absent, `QUESTION_TYPE_SUPPORT` must keep those two
 *    types non-authorable. The day the published contract grows such a field,
 *    this guard fails on purpose and the authoring gate has to be re-decided
 *    instead of silently half-supporting the type;
 *  - `QUESTION_FIELD_LIMITS` equals the contract's `maxLength`/`maxItems`,
 *    and `stem` stays uncapped because the contract gives it only `minLength`;
 *  - `GET /questions` is paged (page/size + a `QuestionPageResponse` envelope)
 *    and the list page takes its size from the contract default, so a pager that
 *    slices a fetched array locally fails here.
 *
 * Skips when the generated contract is not next to this checkout.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  AUTHORABLE_QUESTION_TYPES,
  QUESTION_FIELD_LIMITS,
  QUESTION_TYPES,
  QUESTION_TYPE_SUPPORT,
  PRACTICE_COUNT_LIMITS,
  EXAM_FORM_LIMITS,
  SPACE_FORM_LIMITS,
  KNOWLEDGE_FORM_LIMITS,
  SOURCE_FORM_LIMITS,
  NOTE_FORM_LIMITS,
  QUESTIONS_PAGE_SIZE,
  QUESTION_TYPE_DISPLAY_GAP,
  isAuthorableQuestionType,
  isPublishableQuestionType,
} from './format';

const CONTRACT_PATH = join(
  process.cwd(),
  '..',
  'packages',
  'api-client',
  'src',
  'generated',
  'openapi.json'
);
const QUESTIONS_DIR = join(
  process.cwd(),
  'src',
  'renderer',
  'src',
  'features',
  'questions'
);
const FORM_PATH = join(QUESTIONS_DIR, 'QuestionForm.tsx');
const LIST_PATH = join(QUESTIONS_DIR, 'QuestionsPage.tsx');

const hasContract = existsSync(CONTRACT_PATH);
const contract = hasContract
  ? (JSON.parse(readFileSync(CONTRACT_PATH, 'utf8')) as {
      paths: Record<string, Record<string, unknown>>;
      components: { schemas: Record<string, ContractSchema> };
    })
  : null;
const schemas = contract?.components.schemas ?? {};

interface ContractSchema {
  type?: string;
  maxLength?: number;
  minLength?: number;
  maxItems?: number;
  minItems?: number;
  minimum?: number;
  maximum?: number;
  items?: ContractSchema;
  properties?: Record<string, ContractSchema>;
  required?: string[];
  parameters?: Array<{ name: string; in?: string }>;
}

const schema = (name: string): ContractSchema => schemas[name] ?? {};
const fieldNames = (name: string): string[] =>
  Object.keys(schema(name).properties ?? {});
const maxLengthOf = (name: string, field: string): number | undefined =>
  schema(name).properties?.[field]?.maxLength;
const maxItemsOf = (name: string, field: string): number | undefined =>
  schema(name).properties?.[field]?.maxItems;

/**
 * The practice start form's own numeric bounds, pinned the same way as the
 * question field limits: the form used to hard-code 1..50 while the contract
 * says 1..100, which silently denied a legitimate session size.
 */
describe.skipIf(!hasContract)('practice count bounds follow the contract', () => {
  it('offers exactly the range CreatePracticeSessionRequest.count allows', () => {
    const count = schema('CreatePracticeSessionRequest').properties?.count ?? {};
    expect(count.minimum).toBeDefined();
    expect(count.maximum).toBeDefined();
    expect(PRACTICE_COUNT_LIMITS.min).toBe(count.minimum);
    expect(PRACTICE_COUNT_LIMITS.max).toBe(count.maximum);
  });
});

/**
 * Exam creation bounds, pinned the same way: the form used to accept anything
 * (no title cap, no question-count cap, no score/duration validation) and let
 * the server 400 it.
 */
describe.skipIf(!hasContract)('exam form limits follow the contract', () => {
  it('matches CreateExamRequest and ExamQuestionInput', () => {
    const exam = schema('CreateExamRequest').properties ?? {};
    const input = schema('ExamQuestionInput').properties ?? {};
    expect(EXAM_FORM_LIMITS.titleMaxLength).toBe(exam.title?.maxLength);
    expect(EXAM_FORM_LIMITS.descriptionMaxLength).toBe(exam.description?.maxLength);
    expect(EXAM_FORM_LIMITS.timeLimitMinMinutes).toBe(exam.timeLimitMinutes?.minimum);
    expect(EXAM_FORM_LIMITS.maxQuestions).toBe(exam.questions?.maxItems);
    expect(EXAM_FORM_LIMITS.questionScoreMin).toBe(input.score?.minimum);
  });
});

/**
 * The remaining creation forms carry contract `maxLength` ceilings too; the
 * inputs now take them from these constants, so a widened server limit has to
 * be widened here deliberately rather than by accident.
 */
describe.skipIf(!hasContract)('space / knowledge / source form limits follow the contract', () => {
  it('matches CreateLearningSpaceRequest', () => {
    const p = schema('CreateLearningSpaceRequest').properties ?? {};
    expect(SPACE_FORM_LIMITS.nameMaxLength).toBe(p.name?.maxLength);
    expect(SPACE_FORM_LIMITS.descriptionMaxLength).toBe(p.description?.maxLength);
  });

  it('matches CreateKnowledgeCategoryRequest and CreateKnowledgePointRequest', () => {
    const category = schema('CreateKnowledgeCategoryRequest').properties ?? {};
    const point = schema('CreateKnowledgePointRequest').properties ?? {};
    expect(KNOWLEDGE_FORM_LIMITS.categoryNameMaxLength).toBe(category.name?.maxLength);
    expect(KNOWLEDGE_FORM_LIMITS.categoryDescriptionMaxLength).toBe(
      category.description?.maxLength
    );
    expect(KNOWLEDGE_FORM_LIMITS.pointTitleMaxLength).toBe(point.title?.maxLength);
    expect(KNOWLEDGE_FORM_LIMITS.pointSummaryMaxLength).toBe(point.summary?.maxLength);
    expect(KNOWLEDGE_FORM_LIMITS.pointDifficultyMaxLength).toBe(
      point.difficulty?.maxLength
    );
  });

  it('matches CreateSourceRequest', () => {
    const p = schema('CreateSourceRequest').properties ?? {};
    expect(SOURCE_FORM_LIMITS.titleMaxLength).toBe(p.title?.maxLength);
  });
});

/**
 * Notes creation bounds (G01). `content` is the only required field in the
 * contract, and the list endpoint is paged — unlike questions, which still has
 * no paging params at all.
 */
describe.skipIf(!hasContract)('note form limits follow the contract', () => {
  it('matches NoteCreateRequest and the paged list endpoint', () => {
    const note = schema('NoteCreateRequest').properties ?? {};
    expect(NOTE_FORM_LIMITS.titleMaxLength).toBe(note.title?.maxLength);
    expect(NOTE_FORM_LIMITS.contentMaxLength).toBe(note.content?.maxLength);
    expect(schema('NoteCreateRequest').required).toEqual(['content']);

    const params =
      (contract?.paths?.['/api/v1/spaces/{spaceId}/notes']?.get as
        { parameters?: Array<{ name: string; schema?: { default?: number } }> })
        ?.parameters ?? [];
    const size = params.find((p) => p.name === 'size');
    expect(size).toBeDefined();
    expect(NOTE_FORM_LIMITS.pageSize).toBe(size?.schema?.default);
  });
});

/** Every `body.<field> =` the create form assigns. */
function fieldsWrittenByTheForm(): string[] {
  const source = readFileSync(FORM_PATH, 'utf8');
  return [...source.matchAll(/\bbody\.([A-Za-z0-9_]+)\s*=/g)].map((m) => m[1]);
}

/** Every search-param key the list page reads out of the URL. */
function filtersReadByTheListPage(): string[] {
  const source = readFileSync(LIST_PATH, 'utf8');
  return [
    ...source.matchAll(/searchParams\.get\(['"]([^'"]+)['"]\)/g),
  ].map((m) => m[1]);
}

describe.skipIf(!hasContract)('question authoring body matches the contract', () => {
  it('finds the schemas it is supposed to cover', () => {
    expect(fieldNames('CreateQuestionRequest').length).toBeGreaterThan(0);
    expect(fieldNames('AnswerPayloadView').length).toBeGreaterThan(0);
  });

  it('only writes fields the published CreateQuestionRequest declares', () => {
    const written = fieldsWrittenByTheForm();
    expect(written.length).toBeGreaterThan(3);
    const contractFields = new Set(fieldNames('CreateQuestionRequest'));
    expect(written.filter((f) => !contractFields.has(f))).toEqual([]);
  });

  it('carries the order and pair wire format, which is why both gates are open', () => {
    const create = schema('CreateQuestionRequest').properties ?? {};
    const payload = schema('AnswerPayloadView').properties ?? {};
    expect(Object.keys(payload)).toEqual([
      'selectedOptionKeys',
      'booleanAnswer',
      'textAnswer',
      'orderingAnswer',
      'matchingAnswer',
    ]);
    expect(create.correctOrder?.type).toBe('array');
    expect(create.matches?.type).toBe('string');
    expect(isAuthorableQuestionType('ORDERING')).toBe(true);
    expect(isPublishableQuestionType('ORDERING')).toBe(true);
    expect(isAuthorableQuestionType('MATCHING')).toBe(true);
    expect(isPublishableQuestionType('MATCHING')).toBe(true);
  });

  it('numbers ordering positions from 1, so the renderer must not send 0', () => {
    // AnswerPayloadView.orderingAnswer items carry `minimum: 1` and the server
    // grades ORDERING by exact list equality against the stored correctOrder,
    // so author and answerer share one index space: 1-based option positions.
    const ordering = schema('AnswerPayloadView').properties?.orderingAnswer ?? {};
    expect(ordering.items?.minimum).toBe(1);
  });

  it('cannot read a stored order or pair set back, so the review notice stays', () => {
    // C09b: the write side grew correctOrder / matches, the read side did not.
    expect(fieldNames('QuestionAnswerView')).toEqual([
      'correctOptionKey',
      'correctOptionKeys',
      'correctBoolean',
      'referenceAnswer',
    ]);
    expect(QUESTION_TYPE_DISPLAY_GAP.ORDERING?.review).toContain(
      'QuestionAnswerView'
    );
    expect(QUESTION_TYPE_DISPLAY_GAP.MATCHING?.review).toContain(
      'QuestionAnswerView'
    );
    expect(QUESTION_TYPE_DISPLAY_GAP.SINGLE_CHOICE).toBeUndefined();
  });

  it('carries FILL_BLANK end to end, which is why it is authorable', () => {
    expect(isAuthorableQuestionType('FILL_BLANK')).toBe(true);
    expect(fieldNames('CreateQuestionRequest')).toContain('referenceAnswer');
    expect(fieldNames('AnswerPayloadView')).toContain('textAnswer');
    expect(fieldNames('QuestionAnswerView')).toContain('referenceAnswer');
  });

  it('lists exactly the support matrix says are authorable', () => {
    expect([...AUTHORABLE_QUESTION_TYPES].sort()).toEqual(
      QUESTION_TYPES.filter((t) => QUESTION_TYPE_SUPPORT[t].authorable).sort()
    );
    expect(QUESTION_TYPES.every((t) => t in QUESTION_TYPE_SUPPORT)).toBe(true);
  });
});

describe.skipIf(!hasContract)('question field limits equal the contract limits', () => {
  const expected = {
    questionType: maxLengthOf('CreateQuestionRequest', 'questionType'),
    explanation: maxLengthOf('CreateQuestionRequest', 'explanation'),
    difficulty: maxLengthOf('CreateQuestionRequest', 'difficulty'),
    optionKey: maxLengthOf('QuestionOptionInput', 'optionKey'),
    optionContent: maxLengthOf('QuestionOptionInput', 'content'),
    correctOptionKey: maxLengthOf('CreateQuestionRequest', 'correctOptionKey'),
    correctOptionKeys: maxItemsOf('CreateQuestionRequest', 'correctOptionKeys'),
    referenceAnswer: maxLengthOf('CreateQuestionRequest', 'referenceAnswer'),
    selectedOptionKeys: maxItemsOf('AnswerPayloadView', 'selectedOptionKeys'),
    textAnswer: maxLengthOf('AnswerPayloadView', 'textAnswer'),
    correctOrder: maxItemsOf('CreateQuestionRequest', 'correctOrder'),
    matches: maxLengthOf('CreateQuestionRequest', 'matches'),
    orderingAnswer: maxItemsOf('AnswerPayloadView', 'orderingAnswer'),
    matchingAnswer: maxLengthOf('AnswerPayloadView', 'matchingAnswer'),
  } as const;

  it('pins every declared limit', () => {
    for (const [key, value] of Object.entries(expected)) {
      expect(
        { [key]: QUESTION_FIELD_LIMITS[key as keyof typeof QUESTION_FIELD_LIMITS] },
        `${key} drifted from openapi.json`
      ).toEqual({ [key]: value });
    }
  });

  it('invents no cap the contract does not declare', () => {
    // `stem` is `minLength: 1` only — a UI cap on it would be a rule the
    // server does not have, so it must not appear in the limits map.
    expect(maxLengthOf('CreateQuestionRequest', 'stem')).toBeUndefined();
    expect(QUESTION_FIELD_LIMITS).not.toHaveProperty('stem');
    expect(Object.keys(QUESTION_FIELD_LIMITS).sort()).toEqual(
      Object.keys(expected).sort()
    );
  });
});

describe.skipIf(!hasContract)('question routes offer exactly the verbs the renderer claims', () => {
  const collection = '/api/v1/spaces/{spaceId}/questions';
  const item = '/api/v1/spaces/{spaceId}/questions/{questionId}';

  it('has update, archive and restore, and pins what update may change', () => {
    expect(Object.keys(contract?.paths[collection] ?? {})).toEqual(['get', 'post']);
    expect(Object.keys(contract?.paths[item] ?? {}).sort()).toEqual(['get', 'put']);
    expect(
      Object.keys(contract?.paths[`${item}/publish`] ?? {})
    ).toEqual(['post']);
    expect(
      Object.keys(contract?.paths[`${item}/archive`] ?? {})
    ).toEqual(['post']);
    expect(
      Object.keys(contract?.paths[`${item}/restore`] ?? {})
    ).toEqual(['post']);

    // G04: the edit form may only offer what UpdateQuestionRequest carries.
    // Options and the correct answer are not editable — the detail page says so.
    expect(fieldNames('UpdateQuestionRequest').sort()).toEqual([
      'difficulty',
      'explanation',
      'stem',
    ]);
  });

  it('is paged, so the list page has to drive the server rather than slice', () => {
    const names = (contract?.paths[collection]?.get as { parameters?: Array<{ name: string }> })
      ?.parameters?.map((p) => p.name);
    expect(names).toEqual([
      'spaceId',
      'status',
      'questionType',
      'knowledgePointId',
      'page',
      'size',
    ]);
    // C-Paging closed on 2026-10-14: the endpoint answers with an envelope, and
    // the list page reads its totals from it instead of counting a fetched array.
    const response = schemas['QuestionPageResponse'];
    expect(Object.keys(response?.properties ?? {})).toEqual([
      'items',
      'page',
      'pageSize',
      'totalItems',
      'totalPages',
    ]);
    expect(
      JSON.stringify(response?.properties?.items),
      'items must be an array of $ref question rows'
    ).toContain('$ref');
  });

  it('uses the contract page size, not a number the UI invented', () => {
    const params =
      (contract?.paths?.[collection]?.get as {
        parameters?: Array<{ name: string; schema?: { default?: number } }>;
      })?.parameters ?? [];
    expect(QUESTIONS_PAGE_SIZE).toBe(
      params.find((p) => p.name === 'size')?.schema?.default
    );
  });

  it('only reads filters the endpoint actually accepts', () => {
    const names = (contract?.paths[collection]?.get as { parameters?: Array<{ name: string }> })
      ?.parameters?.map((p) => p.name) ?? [];
    const read = filtersReadByTheListPage();
    expect(read.length).toBeGreaterThan(0);
    expect(read.filter((key) => !names.includes(key))).toEqual([]);
  });

  it('returns the authoring view the detail page renders', () => {
    const itemSchema = contract?.paths[item] as {
      get?: { responses?: Record<string, { content?: Record<string, { schema?: { $ref?: string } }> }> } };
    const ref =
      itemSchema?.get?.responses?.['200']?.content?.['application/json']?.schema?.$ref;
    expect(ref).toBe('#/components/schemas/QuestionAuthoringResponse');
    expect(fieldNames('QuestionAuthoringResponse')).toContain('answer');
    expect(fieldNames('QuestionOptionResponse')).toContain('sortOrder');
  });
});

describe('question type vocabulary', () => {
  it('names the seven backend types and their display copy', () => {
    expect(QUESTION_TYPES).toEqual([
      'SINGLE_CHOICE',
      'MULTIPLE_CHOICE',
      'TRUE_FALSE',
      'SHORT_ANSWER',
      'FILL_BLANK',
      'ORDERING',
      'MATCHING',
    ]);
  });
});
