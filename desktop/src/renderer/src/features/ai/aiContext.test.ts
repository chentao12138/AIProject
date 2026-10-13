/**
 * A3.2 (G07) + A3.3 (G08) contract tests for `aiContext.ts`.
 *
 * These assert BEHAVIOUR — where a citation opens, which ids reach the request
 * body — not markup. The two failure modes they exist to catch:
 *
 *  1. a citation routed off `locator` or off `id` for a source-tree type, which
 *     sends the learner to the wrong page (or to a deleted row) while looking
 *     perfectly plausible;
 *  2. a message that claims context the body does not carry, or carries an id
 *     the learner never attached. Both are now possible because
 *     `SendMessageRequest` accepts four optional ids, so "what is attached" is
 *     a contract question, not a copy question.
 */

import { readFileSync, existsSync } from 'node:fs';
import { join, posix } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router-dom';
import { router } from '../../app/router';
import {
  AI_ATTACH_PARAM_KEYS,
  attachmentBody,
  attachmentSearch,
  attachmentSummary,
  citationHref,
  citationLabel,
  CITATION_TYPES,
  hasAttachedContext,
  messageBodyWithAttachment,
  readAiAttachment,
  type AiAttachedContext,
  type AiCitation,
} from './aiContext';

const SPACE = 7;

/** `?a=b` from the AI route's own query string. */
function params(query: string): URLSearchParams {
  return new URLSearchParams(query);
}

describe('citation routing (A3.3 / G08)', () => {
  it('routes every citable type to the route that entity lives on', () => {
    const cases: Array<[AiCitation, string]> = [
      [
        { type: 'SOURCE', id: 5, sourceId: 5 },
        `/spaces/${SPACE}/sources/5`,
      ],
      [
        { type: 'SOURCE_PAGE', id: 20, sourceId: 5, sourcePageId: 20 },
        `/spaces/${SPACE}/sources/5?page=20`,
      ],
      [
        {
          type: 'CONTENT_BLOCK',
          id: 31,
          sourceId: 5,
          sourcePageId: 20,
          contentBlockId: 31,
        },
        `/spaces/${SPACE}/sources/5?page=20&block=31`,
      ],
      [
        { type: 'KNOWLEDGE_POINT', id: 10 },
        `/spaces/${SPACE}/knowledge/10`,
      ],
      [{ type: 'QUESTION', id: 12 }, `/spaces/${SPACE}/questions/12`],
      [{ type: 'NOTE', id: 15 }, `/spaces/${SPACE}/notes/15`],
    ];
    for (const [ref, expected] of cases) {
      expect(citationHref(SPACE, ref)).toBe(expected);
    }
  });

  it('names every route it can produce against the real registered router', () => {
    // Not-navigable is `null`; anything non-null must be a route this app has.
    const refs: AiCitation[] = [
      { type: 'SOURCE', id: 5, sourceId: 5 },
      { type: 'SOURCE_PAGE', id: 20, sourceId: 5, sourcePageId: 20 },
      {
        type: 'CONTENT_BLOCK',
        id: 31,
        sourceId: 5,
        sourcePageId: 20,
        contentBlockId: 31,
      },
      { type: 'KNOWLEDGE_POINT', id: 10 },
      { type: 'QUESTION', id: 12 },
      { type: 'NOTE', id: 15 },
    ];
    const unresolvable: string[] = [];
    for (const ref of refs) {
      const href = citationHref(SPACE, ref);
      if (href === null || !ROUTES.some((pattern) => matchesRoute(pattern, href))) {
        unresolvable.push(`${JSON.stringify(ref)} -> ${String(href)}`);
      }
    }
    expect(unresolvable).toEqual([]);
  });

  it('WRONG_QUESTION has no destination, because no question id travels with it', () => {
    // `id` is the wrong_question ROW (SearchMapper: `wq.id AS entity_id`,
    // `wq.question_id AS question_id`) and ContextReference has no questionId
    // field — so any link here would open some other question.
    expect(
      citationHref(SPACE, {
        type: 'WRONG_QUESTION',
        id: 44,
        sourceId: 5,
        sourcePageId: 20,
      })
    ).toBeNull();
  });

  it('never links a source-tree citation without the structured id that owns it', () => {
    const cases: AiCitation[] = [
      // V065 nulls the pointer columns ON DELETE SET NULL while keeping the
      // citation row, so `id` alone can name a deleted entity.
      { type: 'SOURCE', id: 5 },
      { type: 'SOURCE', id: 5, sourceId: 0 },
      { type: 'SOURCE_PAGE', id: 20, sourcePageId: 20 },
      { type: 'CONTENT_BLOCK', id: 31, contentBlockId: 31, sourcePageId: 20 },
      { type: 'KNOWLEDGE_POINT', id: 0 },
      { type: 'QUESTION' },
      { type: 'NOTE', id: -3 },
      // A type this build does not know must not be guessed at either.
      { type: 'FLASHCARD', id: 9, sourceId: 5 },
      { id: 9, sourceId: 5 },
    ];
    const unroutable: string[] = [];
    for (const ref of cases) {
      const href = citationHref(SPACE, ref);
      if (href !== null) {
        unroutable.push(`${JSON.stringify(ref)} -> ${href}`);
      }
    }
    expect(unroutable).toEqual([]);
  });

  it('never emits block without a page, and never a block id it does not have', () => {
    // No page id: the Source itself is still a real destination.
    expect(
      citationHref(SPACE, {
        type: 'CONTENT_BLOCK',
        id: 31,
        sourceId: 5,
        contentBlockId: 31,
      })
    ).toBe(`/spaces/${SPACE}/sources/5`);
    // Page present, block pointer dropped server-side: page only, no `block`,
    // and never a `block=31` rebuilt from `id`.
    expect(
      citationHref(SPACE, {
        type: 'CONTENT_BLOCK',
        id: 31,
        sourceId: 5,
        sourcePageId: 20,
      })
    ).toBe(`/spaces/${SPACE}/sources/5?page=20`);
  });

  it('treats locator as display text only — it never reaches the route', () => {
    // `locator` is "P" + DOCUMENT page number (AiContextItem.java:44); the
    // reader selects on the page ROW id. 12 vs 20 below is the whole bug.
    const ref: AiCitation = {
      type: 'CONTENT_BLOCK',
      id: 31,
      sourceId: 5,
      sourcePageId: 20,
      contentBlockId: 31,
      locator: 'P12',
    };
    const href = citationHref(SPACE, ref) as string;
    expect(href).toBe(`/spaces/${SPACE}/sources/5?page=20&block=31`);
    expect(href).not.toContain('12');
    // And a locator alone buys nothing: with no structured ids there is no
    // destination, however parseable the label looks.
    expect(
      citationHref(SPACE, { type: 'CONTENT_BLOCK', id: 31, locator: 'P12' })
    ).toBeNull();
    expect(
      citationHref(SPACE, { type: 'SOURCE_PAGE', id: 20, locator: 'P3' })
    ).toBeNull();
  });

  it('reads the type the way the contract writes it', () => {
    // Whitespace / case are the server's own vocabulary, not a new one.
    expect(citationHref(SPACE, { type: ' knowledge_point ', id: 10 })).toBe(
      `/spaces/${SPACE}/knowledge/10`
    );
    expect(citationLabel('SOURCE_PAGE')).toBe('来源页');
    expect(citationLabel('WRONG_QUESTION')).toBe('错题');
    // An unseen type shows as sent rather than as a wrong Chinese name.
    expect(citationLabel('FLASHCARD')).toBe('FLASHCARD');
  });

  it('covers the whole reference_type vocabulary the backend can write', () => {
    expect([...CITATION_TYPES]).toEqual([
      'SOURCE',
      'SOURCE_PAGE',
      'CONTENT_BLOCK',
      'KNOWLEDGE_POINT',
      'QUESTION',
      'WRONG_QUESTION',
      'NOTE',
    ]);
  });
});

/* The registered routes, read off the real router config (no casts). */
function collectRoutes(routes: readonly RouteObject[], parent = ''): string[] {
  const out: string[] = [];
  for (const route of routes) {
    const raw = (route.path ?? '').replace(/\\/g, '/');
    if (raw.startsWith('*')) {
      continue;
    }
    const full = posix.join('/', parent, raw);
    out.push(full);
    out.push(...collectRoutes(route.children ?? [], full));
  }
  return out;
}
const ROUTES = collectRoutes(router.routes);

function matchesRoute(pattern: string, target: string): boolean {
  const path = target.replace(/^#/, '').split('?')[0];
  const p = pattern.split('/').filter(Boolean);
  const t = path.split('/').filter(Boolean);
  if (p.length !== t.length) {
    return false;
  }
  return p.every(
    (segment, i) =>
      segment.startsWith(':') || t[i].startsWith(':') || segment === t[i]
  );
}

/**
 * The backend's own entity vocabulary, read from the same monorepo (the same
 * trick `lib/backend-vocabulary-contract.test.ts` uses). `ContextReference.type`
 * is a bare `string` in the contract, so TypeScript cannot see a member added by
 * a migration — this can.
 */
const SEARCH_ENTITY_ENUM = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'java',
  'com',
  'aistudy',
  'server',
  'search',
  'type',
  'SearchEntityType.java'
);

describe.skipIf(!existsSync(SEARCH_ENTITY_ENUM))(
  'citation vocabulary tracks the backend',
  () => {
    it('routes every search entity type the backend can name', () => {
      const src = readFileSync(SEARCH_ENTITY_ENUM, 'utf8');
      const names = [...src.matchAll(/enum\s+SearchEntityType\s*\{([\s\S]*?)\}/g)]
        .flatMap((match) =>
          match[1]
            .replace(/\/\*[\s\S]*?\*\//g, '')
            .split(',')
            .map((entry) => entry.trim())
            .filter((entry) => /^[A-Z][A-Z0-9_]*$/.test(entry))
        );
      expect(names.length).toBeGreaterThan(0);
      const missing = names.filter((name) => !CITATION_TYPES.includes(name as never));
      expect(missing).toEqual([]);
    });
  }
);

describe('message context attachment (A3.2 / G07)', () => {
  it('parses only positive integer ids and reports the rest', () => {
    expect(
      readAiAttachment(params('attachSourceId=5')).context
    ).toEqual({ sourceId: 5 });
    expect(
      readAiAttachment(params('attachContentBlockId=31')).context
    ).toEqual({ contentBlockId: 31 });
    expect(
      readAiAttachment(params('attachKnowledgePointId=10')).context
    ).toEqual({ knowledgePointId: 10 });
    // Unusable: never sent, and named in the UI instead of dropped silently.
    const garbage = readAiAttachment(
      params('attachSourceId=abc&attachSourcePageId=0&attachContentBlockId=-2')
    );
    expect(garbage.context).toEqual({});
    expect([...garbage.rejected].sort()).toEqual(
      ['attachContentBlockId', 'attachSourcePageId', 'attachSourceId'].sort()
    );
    expect(readAiAttachment(params('')).context).toEqual({});
    expect(readAiAttachment(params('attachSourceId=')).rejected).toEqual([]);
  });

  it('keeps the narrowest source pointer, which is all the server needs', () => {
    // resolveSelectedContext derives page+source from the block
    // (AiLearningContextService.java:152-158) and rejects a sourceId that
    // disagrees with the page it was given (:165-166) — so the body carries one
    // pointer, never a trio that can contradict itself.
    expect(
      readAiAttachment(
        params(
          'attachSourceId=5&attachSourcePageId=20&attachContentBlockId=31'
        )
      ).context
    ).toEqual({ contentBlockId: 31 });
    expect(
      readAiAttachment(params('attachSourceId=5&attachSourcePageId=20')).context
    ).toEqual({ sourcePageId: 20 });
    // A knowledge point is an independent branch server-side, so it coexists.
    expect(
      readAiAttachment(params('attachSourceId=5&attachKnowledgePointId=10'))
        .context
    ).toEqual({ sourceId: 5, knowledgePointId: 10 });
  });

  it('builds a body that carries exactly the attached ids and nothing else', () => {
    expect(messageBodyWithAttachment('为什么？', {})).toEqual({
      content: '为什么？',
    });
    expect(
      messageBodyWithAttachment('为什么？', { contentBlockId: 31 })
    ).toEqual({ content: '为什么？', contentBlockId: 31 });
    expect(
      messageBodyWithAttachment('为什么？', {
        sourceId: 5,
        knowledgePointId: 10,
      })
    ).toEqual({ content: '为什么？', sourceId: 5, knowledgePointId: 10 });
    // An id that is not a positive id is not a field of the body — so a hand-
    // edited URL can never send `sourceId: 0` and get the message rejected.
    expect(
      messageBodyWithAttachment('x', { sourceId: 0, contentBlockId: -1 })
    ).toEqual({ content: 'x' });
    expect(attachmentBody({ sourceId: 0 })).toEqual([]);
    expect(hasAttachedContext({ sourceId: 0 })).toBe(false);
  });

  it('shows the learner what is attached, in the same order the body carries it', () => {
    expect(
      attachmentSummary({ contentBlockId: 31, knowledgePointId: 10 })
    ).toEqual(['正文片段 #31', '知识点 #10']);
    expect(attachmentSummary({ sourcePageId: 20 })).toEqual(['来源页 #20']);
    expect(attachmentSummary({ sourceId: 5 })).toEqual(['学习来源 #5']);
    expect(attachmentSummary({})).toEqual([]);
  });

  it('round-trips the attachment through the URL it survives navigation on', () => {
    const search = attachmentSearch({ sourcePageId: 20 });
    expect(search).toBe('?attachSourcePageId=20');
    expect(readAiAttachment(params(search.slice(1))).context).toEqual({
      sourcePageId: 20,
    });
    // Nothing attached -> no query string at all, so an ordinary /ai link stays
    // free of an empty attachment marker.
    expect(attachmentSearch({})).toBe('');
  });

  it('understands every attach key the other modules actually link with', () => {
    // features/sources and features/knowledge may not import this module (the
    // import-graph rule keeps features independent), so they write the key
    // names as literals. A typo there would drop the attachment in silence —
    // this scan is what makes it fail a test instead.
    const knownKeys = new Set<string>(Object.values(AI_ATTACH_PARAM_KEYS));
    const writers = [
      'src/renderer/src/features/sources/SourceWorkbenchPage.tsx',
      'src/renderer/src/features/knowledge/KnowledgePointDetailPage.tsx',
    ];
    const emitted = new Set<string>();
    for (const file of writers) {
      // Only a key position counts: prose about attaching/attached material is
      // not part of the vocabulary.
      for (const match of readFileSync(
        join(process.cwd(), file),
        'utf8'
      ).matchAll(/\battach[A-Za-z]+(?=\s*:)/g)) {
        emitted.add(match[0]);
      }
    }
    expect([...emitted].sort()).toEqual([...knownKeys].sort());
    // Every key must be one this page reads back as its own context field —
    // the pair is the contract, so it is inverted from the module's own table
    // rather than by renaming the string here.
    type AttachEntry = [keyof AiAttachedContext, string];
    const fieldOfKey = new Map<string, keyof AiAttachedContext>(
      (Object.entries(AI_ATTACH_PARAM_KEYS) as AttachEntry[]).map(
        ([field, key]) => [key, field]
      )
    );
    const mismapped: string[] = [];
    for (const key of knownKeys) {
      const field = fieldOfKey.get(key);
      const parsed = readAiAttachment(params(`${key}=5`));
      if (field === undefined || parsed.context[field] !== 5) {
        mismapped.push(`${key} is not read as a context field`);
      }
    }
    expect(mismapped).toEqual([]);
  });
});
