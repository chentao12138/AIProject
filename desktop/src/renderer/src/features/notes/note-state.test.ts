/**
 * Note-form rules (G01 / A8.1). Each expectation names the server behaviour it
 * mirrors, from `server/src/main/java/com/aistudy/server/note/service/NoteService.java`.
 */

import { describe, expect, it } from 'vitest';
import { NOTE_FORM_LIMITS } from '../../lib/format';
import {
  buildNoteBody,
  contentFormatLabel,
  draftOfNote,
  emptyDraft,
  isNoteContentFormat,
  NOTE_CONTENT_FORMATS,
  noteDisplayTitle,
  noteExcerpt,
  parseNotePageParam,
  pointerValueOf,
  readNotePrefill,
  sameNoteDraft,
  sourcePointerOf,
  sourceReaderHref,
  validateNoteDraft,
  withPointerValue,
  type NoteDocument,
  type NoteDraft,
} from './note-state';

const OVERSIZED = 'x'.repeat(NOTE_FORM_LIMITS.contentMaxLength + 1);

function draft(overrides: Partial<NoteDraft> = {}): NoteDraft {
  return {
    title: '',
    content: '正文内容',
    contentFormat: NOTE_FORM_LIMITS.defaultContentFormat,
    sourcePointer: 'none',
    sourceId: '',
    sourcePageId: '',
    contentBlockId: '',
    knowledgePointId: '',
    questionId: '',
    ...overrides,
  };
}

const storedNote: NoteDocument = {
  id: 42,
  spaceId: 7,
  title: '矩阵笔记',
  content: '特征值',
  contentFormat: 'MARKDOWN',
  sourceId: 3,
  sourcePageId: 8,
  contentBlockId: 99,
  knowledgePointId: 12,
  questionId: 5,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-02T08:00:00Z',
};

describe('content format (NoteService.normalizeFormat)', () => {
  it('offers exactly the two formats the server stores', () => {
    expect(NOTE_CONTENT_FORMATS).toEqual([...NOTE_FORM_LIMITS.contentFormats]);
    expect(isNoteContentFormat('PLAIN_TEXT')).toBe(true);
    expect(isNoteContentFormat('MARKDOWN')).toBe(true);
    expect(isNoteContentFormat('HTML')).toBe(false);
    expect(isNoteContentFormat('')).toBe(false);
    expect(isNoteContentFormat(null)).toBe(false);
  });

  it('refuses to submit a format the backend would 400 on', () => {
    const message = validateNoteDraft(draft({ contentFormat: 'HTML' })).join(' ');
    expect(message).toContain('正文格式');
    expect(message).toContain('PLAIN_TEXT');
    expect(message).toContain('MARKDOWN');
    expect(
      validateNoteDraft(draft({ contentFormat: 'MARKDOWN' }))
    ).not.toContain('正文格式');
  });

  it('labels the stored value for humans without inventing a third one', () => {
    expect(contentFormatLabel('markdown')).toBe('Markdown');
    expect(contentFormatLabel('PLAIN_TEXT')).toBe('纯文本');
    expect(contentFormatLabel('HTML')).toBe('HTML');
    expect(contentFormatLabel(undefined)).toBe('—');
  });
});

describe('validateNoteDraft', () => {
  it('requires content, because NoteCreateRequest.content is @NotBlank', () => {
    expect(validateNoteDraft(draft({ content: '   ' }))).toContain('正文不能为空。');
    expect(validateNoteDraft(draft())).toEqual([]);
  });

  it('does not demand a title the server does not require', () => {
    expect(validateNoteDraft(draft({ title: '' }))).toEqual([]);
  });

  it('keeps its length caps on the contract numbers', () => {
    const contentError = validateNoteDraft(draft({ content: OVERSIZED }))[0];
    expect(contentError).toContain(String(NOTE_FORM_LIMITS.contentMaxLength));

    const titleError = validateNoteDraft(
      draft({ title: 'y'.repeat(NOTE_FORM_LIMITS.titleMaxLength + 1) })
    )[0];
    expect(titleError).toContain(String(NOTE_FORM_LIMITS.titleMaxLength));
  });

  it('reports a malformed context id instead of dropping it', () => {
    for (const bad of ['abc', '1.5', '0', '-4']) {
      expect(
        validateNoteDraft(
          draft({ sourcePointer: 'block', contentBlockId: bad })
        ).join(' ')
      ).toContain('正文片段必须是正整数。');
      expect(
        validateNoteDraft(draft({ knowledgePointId: bad })).join(' ')
      ).toContain('知识点 ID必须是正整数。');
    }
  });

  it('ignores the source pointers the form is not editing', () => {
    // Only the active pointer reaches the server, so only it can be wrong.
    expect(
      validateNoteDraft(
        draft({ sourcePointer: 'none', sourceId: 'nonsense' })
      )
    ).toEqual([]);
  });
});

describe('buildNoteBody (full-document replacement)', () => {
  it('always sends content and contentFormat', () => {
    const body = buildNoteBody(draft({ contentFormat: 'markdown' }));
    expect(body.content).toBe('正文内容');
    expect(body.contentFormat).toBe('MARKDOWN');
  });

  it('omits an empty title instead of sending an empty string', () => {
    const body = buildNoteBody(draft({ title: '   ' }));
    expect(Object.keys(body)).not.toContain('title');
    expect(buildNoteBody(draft({ title: ' 摘要 ' })).title).toBe('摘要');
  });

  it('keeps the knowledge point and question pointers, which PUT would clear', () => {
    const body = buildNoteBody(
      draft({ knowledgePointId: '12', questionId: '5' })
    );
    expect(body).toEqual({
      content: '正文内容',
      contentFormat: 'PLAIN_TEXT',
      knowledgePointId: 12,
      questionId: 5,
    });
  });

  it('sends one source pointer, never the trio the server overwrites', () => {
    const body = buildNoteBody(
      draft({
        sourcePointer: 'block',
        contentBlockId: '99',
        sourcePageId: '8',
        sourceId: '3',
      })
    );
    expect(body).toEqual({
      content: '正文内容',
      contentFormat: 'PLAIN_TEXT',
      contentBlockId: 99,
    });

    const pageBody = buildNoteBody(
      draft({ sourcePointer: 'page', sourcePageId: '8', sourceId: '3' })
    );
    expect(pageBody).toEqual({
      content: '正文内容',
      contentFormat: 'PLAIN_TEXT',
      sourcePageId: 8,
    });
  });

  it('round-trips a stored note without losing any pointer', () => {
    const body = buildNoteBody(draftOfNote(storedNote));
    expect(body).toEqual({
      content: '特征值',
      contentFormat: 'MARKDOWN',
      title: '矩阵笔记',
      contentBlockId: 99,
      knowledgePointId: 12,
      questionId: 5,
    });
  });
});

describe('source pointer precedence (NoteService.applyContext)', () => {
  it('names the most specific pointer the note carries', () => {
    expect(sourcePointerOf(storedNote)).toBe('block');
    expect(sourcePointerOf({ sourceId: 3, sourcePageId: 8 })).toBe('page');
    expect(sourcePointerOf({ sourceId: 3 })).toBe('source');
    expect(sourcePointerOf({ knowledgePointId: 12 })).toBe('none');
    expect(sourcePointerOf({ contentBlockId: 0 })).toBe('none');
  });

  it('edits only the active pointer input', () => {
    const next = withPointerValue(draft(), 'page', '8');
    expect(pointerValueOf(next, 'page')).toBe('8');
    expect(next.sourceId).toBe('');
  });
});

describe('sameNoteDraft (the dirty flag behind A8.2)', () => {
  it('is clean until something the server stores changes', () => {
    const base = draftOfNote(storedNote);
    expect(sameNoteDraft(base, draftOfNote(storedNote))).toBe(true);
    expect(sameNoteDraft(base, draftOfNote({ ...storedNote, content: '其它' }))).toBe(
      false
    );
    expect(
      sameNoteDraft(base, draftOfNote({ ...storedNote, contentFormat: 'PLAIN_TEXT' }))
    ).toBe(false);
    expect(
      sameNoteDraft(base, draftOfNote({ ...storedNote, knowledgePointId: undefined }))
    ).toBe(false);
  });

  it('stays clean when the response only fills in the pointers the server derives', () => {
    // The form sends contentBlockId alone; the stored note comes back with the
    // page and source derived from it (NoteView javadoc). That is the same
    // document, so saving must not look like a new set of edits.
    const edited = emptyDraft({ contentBlockId: 99 });
    const withBody: NoteDraft = { ...edited, content: '特征值' };
    const serverCopy = draftOfNote(storedNote);
    expect(sameNoteDraft(withBody, serverCopy)).toBe(false);
    const blockOnly = draftOfNote({
      ...storedNote,
      sourceId: undefined,
      sourcePageId: undefined,
    });
    expect(sameNoteDraft(draftOfNote(storedNote), blockOnly)).toBe(true);
  });
});

describe('readNotePrefill (the W16 entry point)', () => {
  it('reads every context id the contract accepts', () => {
    const prefill = readNotePrefill(
      new URLSearchParams('knowledgePointId=12&questionId=5&sourceId=3&page=2&keyword=math')
    );
    expect(prefill.context).toEqual({ knowledgePointId: 12, questionId: 5, sourceId: 3 });
    expect(prefill.rejected).toEqual([]);
    expect(prefill.contentFormat).toBeNull();
  });

  it('reports a value it cannot use instead of attaching a broken pointer', () => {
    const prefill = readNotePrefill(
      new URLSearchParams('knowledgePointId=abc&contentBlockId=0&contentFormat=HTML')
    );
    expect(prefill.context).toEqual({});
    expect(prefill.rejected.sort()).toEqual([
      'contentBlockId',
      'contentFormat',
      'knowledgePointId',
    ]);
  });

  it('honours a supported contentFormat and defaults otherwise', () => {
    expect(
      readNotePrefill(new URLSearchParams('contentFormat=markdown')).contentFormat
    ).toBe('MARKDOWN');
    expect(emptyDraft({}, 'MARKDOWN').contentFormat).toBe('MARKDOWN');
    expect(emptyDraft({}, 'HTML').contentFormat).toBe(
      NOTE_FORM_LIMITS.defaultContentFormat
    );
    expect(emptyDraft({ knowledgePointId: 12 }).knowledgePointId).toBe('12');
  });
});

describe('list display helpers', () => {
  it('parses the server page number strictly (0-based, default 0)', () => {
    expect(parseNotePageParam(null)).toBe(0);
    expect(parseNotePageParam('0')).toBe(0);
    expect(parseNotePageParam('3')).toBe(3);
    expect(parseNotePageParam('-2')).toBe(0);
    expect(parseNotePageParam('abc')).toBe(0);
    expect(parseNotePageParam('1.5')).toBe(0);
  });

  it('renders an untitled note by its first line, as the backend documents', () => {
    expect(
      noteDisplayTitle({ title: '  ', content: '第一行\n第二行' })
    ).toBe('第一行');
    expect(noteDisplayTitle({ title: '矩阵', content: 'x' })).toBe('矩阵');
    expect(noteDisplayTitle({ content: '' })).toBe('未命名笔记');
    expect(noteDisplayTitle({ content: 'x'.repeat(80) })).toBe(
      `${'x'.repeat(60)}…`
    );
  });

  it('collapses a body excerpt to one bounded line', () => {
    expect(noteExcerpt('a\n  b\tc')).toBe('a b c');
    expect(noteExcerpt('')).toBe('');
    expect(noteExcerpt('y'.repeat(200), 120)).toBe(`${'y'.repeat(120)}…`);
  });

  it('only links a source reference that has a reader route', () => {
    expect(
      sourceReaderHref(7, { sourceId: 3, sourcePageId: 8, contentBlockId: 99 })
    ).toBe('/spaces/7/sources/3?page=8&block=99');
    expect(sourceReaderHref(7, { sourceId: 3 })).toBe('/spaces/7/sources/3');
    expect(sourceReaderHref(7, { sourcePageId: 8, contentBlockId: 99 })).toBeNull();
  });
});
