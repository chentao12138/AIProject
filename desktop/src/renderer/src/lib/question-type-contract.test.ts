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
 *  - the question routes stay GET/POST-only (no PUT / DELETE / archive), and
 *    `GET /questions` stays free of paging params, which is exactly why the
 *    list page has no pager.
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
    expect(EXAM_FORM_LIMITS.durationMinMinutes).toBe(exam.durationMinutes?.minimum);
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

  it('cannot express a correct order or a pair set, so the gate stays closed', () => {
    // C09: the backend Java record has `correctOrder` / `matches` and an
    // `orderingAnswer` / `matchingAnswer` payload; neither reached the
    // published contract, so the renderer must not pretend otherwise.
    expect(fieldNames('CreateQuestionRequest')).not.toContain('correctOrder');
    expect(fieldNames('CreateQuestionRequest')).not.toContain('matches');
    expect(fieldNames('AnswerPayloadView')).toEqual([
      'selectedOptionKeys',
      'booleanAnswer',
      'textAnswer',
    ]);
    expect(fieldNames('QuestionAnswerView')).toEqual([
      'correctOptionKey',
      'correctOptionKeys',
      'correctBoolean',
      'referenceAnswer',
    ]);
    expect(isAuthorableQuestionType('ORDERING')).toBe(false);
    expect(isPublishableQuestionType('ORDERING')).toBe(false);
    expect(isAuthorableQuestionType('MATCHING')).toBe(false);
    expect(isPublishableQuestionType('MATCHING')).toBe(false);
  });

  it('has no pair structure anywhere in the contract', () => {
    const json = JSON.stringify(schemas);
    for (const invented of ['pairs', 'matchesJson', 'orderingAnswer', 'matchingAnswer', 'correctOrder']) {
      expect(json, `contract unexpectedly declares ${invented}`).not.toContain(
        `"${invented}"`
      );
    }
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

  it('has no update, delete or archive verb (G04 / G09)', () => {
    expect(Object.keys(contract?.paths[collection] ?? {})).toEqual(['get', 'post']);
    expect(Object.keys(contract?.paths[item] ?? {})).toEqual(['get']);
    expect(
      Object.keys(
        contract?.paths[`${item}/publish`] ?? {}
      )
    ).toEqual(['post']);
  });

  it('has no paging params, so the list page must not fake a pager (C-Paging)', () => {
    const names = (contract?.paths[collection]?.get as { parameters?: Array<{ name: string }> })
      ?.parameters?.map((p) => p.name);
    expect(names).toEqual(['spaceId', 'status', 'questionType', 'knowledgePointId']);
    expect(names).not.toContain('page');
    expect(names).not.toContain('size');
    expect(names).not.toContain('keyword');
    expect(names).not.toContain('difficulty');
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
