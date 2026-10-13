/**
 * Note list behaviour (G01 / A8.1, W16 entry point).
 *
 * The assertions are about the wire, not the markup: which params reach
 * `GET /notes`, what body `POST /notes` receives, and that a rejected submit
 * still reaches the user. Rendering presence proves none of that.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { NotesPage } from './NotesPage';
import { ApiClientProvider } from '../../lib/api-context';
import { createTestQueryClient, mockApiClient } from '../../test/test-utils';
import { NOTE_FORM_LIMITS } from '../../lib/format';
import { queryKeys } from '../../lib/query-keys';
import {
  NOTE_CONTENT_FORMATS,
  type NoteCreateBody,
  type NoteListQuery,
} from './note-state';

const PAGE_SIZE = NOTE_FORM_LIMITS.pageSize;
/** A fresh empty-list mock per test, so call counts never leak between them. */
const noNotes = () =>
  vi.fn(async () => ({
    data: {
      items: [],
      page: 0,
      pageSize: PAGE_SIZE,
      totalItems: 0,
      totalPages: 0,
    },
    response: { status: 200 },
  }));

function noteRow(id: number | undefined, overrides: Record<string, unknown> = {}) {
  return {
    id,
    spaceId: 7,
    title: `笔记 ${id}`,
    content: `第 ${id} 条正文`,
    contentFormat: 'PLAIN_TEXT',
    updatedAt: '2026-10-02T08:00:00Z',
    ...overrides,
  };
}

/** A `NotePageResponse`, carrying the server's own totals. */
function pageResponse(items: unknown[], over: Record<string, unknown> = {}) {
  return {
    data: {
      items,
      page: 0,
      pageSize: PAGE_SIZE,
      totalItems: items.length,
      totalPages: 1,
      ...over,
    },
    response: { status: 200 },
  };
}

const createdNote = {
  data: { id: 42, spaceId: 7, content: '特征值', contentFormat: 'PLAIN_TEXT' },
  response: { status: 201 },
};

/**
 * Two real routes: arriving at `/notes` and being navigated to `/notes/:noteId`
 * is the deliverable, so the harness registers both and the test asserts the
 * landing page rendered rather than reading a spy.
 */
function renderNotes({
  api,
  url = '/spaces/7/notes',
  queryClient = createTestQueryClient(),
}: {
  api: ApiClient;
  url?: string;
  queryClient?: QueryClient;
}) {
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={api}>
        <MemoryRouter initialEntries={[url]}>
          <Routes>
            <Route path="/spaces/:spaceId/notes" element={<NotesPage />} />
            <Route
              path="/spaces/:spaceId/notes/:noteId"
              element={<div>笔记详情桩</div>}
            />
          </Routes>
        </MemoryRouter>
      </ApiClientProvider>
    </QueryClientProvider>
  );
  return queryClient;
}

describe('NotesPage server-side paging (A8.1)', () => {
  it('asks the backend for one page and renders exactly what it returned', async () => {
    const listNotes = vi.fn(async (_spaceId: number, query?: NoteListQuery) => {
      const page = query?.page ?? 0;
      const items =
        page === 0
          ? Array.from({ length: PAGE_SIZE }, (_, index) => noteRow(index + 1))
          : [noteRow(21), noteRow(22)];
      return pageResponse(items, { page, totalItems: 45, totalPages: 3 });
    });

    renderNotes({ api: mockApiClient({ listNotes }) });

    await screen.findByText('笔记 1');
    expect(listNotes).toHaveBeenCalledWith(7, {
      page: 0,
      size: PAGE_SIZE,
      keyword: undefined,
    });
    // All 20 rows of page 0 are on screen — nothing was sliced away locally.
    expect(screen.getAllByRole('link', { name: /^笔记 \d+$/ })).toHaveLength(
      PAGE_SIZE
    );
    expect(screen.getByTestId('note-count').textContent).toContain('45');
    expect(screen.getByTestId('note-count').textContent).toContain(
      String(PAGE_SIZE)
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '下一页' }));

    await waitFor(() => expect(screen.getByText('笔记 21')).toBeInTheDocument());
    expect(listNotes).toHaveBeenLastCalledWith(7, {
      page: 1,
      size: PAGE_SIZE,
      keyword: undefined,
    });
    // Page 1 replaced page 0 instead of appending to it.
    expect(screen.queryByText('笔记 1')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /^笔记 \d+$/ })).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: '上一页' }));
    await waitFor(() => expect(screen.getByText('笔记 1')).toBeInTheDocument());
    expect(listNotes).toHaveBeenLastCalledWith(7, {
      page: 0,
      size: PAGE_SIZE,
      keyword: undefined,
    });
  });

  it('sends the keyword to the server instead of filtering the loaded page', async () => {
    const listNotes = vi.fn(async () =>
      pageResponse([noteRow(9, { title: '三角函数笔记' })])
    );
    renderNotes({ api: mockApiClient({ listNotes }) });

    const user = userEvent.setup();
    await screen.findByRole('link', { name: '三角函数笔记' });
    await user.type(screen.getByLabelText('关键词'), '三角函数');
    await user.click(screen.getByRole('button', { name: '搜索' }));

    await waitFor(() =>
      expect(listNotes).toHaveBeenLastCalledWith(7, {
        page: 0,
        size: PAGE_SIZE,
        keyword: '三角函数',
      })
    );
    expect(screen.getByRole('link', { name: '三角函数笔记' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '清除关键词' }));
    await waitFor(() =>
      expect(listNotes).toHaveBeenLastCalledWith(7, {
        page: 0,
        size: PAGE_SIZE,
        keyword: undefined,
      })
    );
  });

  it('restarts at page 0 when the keyword changes', async () => {
    const listNotes = vi.fn(async (_spaceId: number, query?: NoteListQuery) =>
      pageResponse([noteRow(1)], {
        page: query?.page ?? 0,
        totalItems: 45,
        totalPages: 3,
      })
    );
    renderNotes({
      api: mockApiClient({ listNotes }),
      url: '/spaces/7/notes?page=2',
    });

    const user = userEvent.setup();
    await screen.findByText('笔记 1');
    await user.type(screen.getByLabelText('关键词'), 'new');
    await user.click(screen.getByRole('button', { name: '搜索' }));

    await waitFor(() =>
      expect(listNotes).toHaveBeenLastCalledWith(7, {
        page: 0,
        size: PAGE_SIZE,
        keyword: 'new',
      })
    );
  });
});

describe('NotesPage create (A8.1)', () => {
  it('POSTs the contract body, invalidates the list and routes to the new note', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );
    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    renderNotes({
      api: mockApiClient({ createNote, listNotes: noNotes() }),
      queryClient,
    });

    const user = userEvent.setup();
    await screen.findByRole('heading', { name: '笔记' });
    await user.type(screen.getByLabelText('标题（可选）'), '矩阵');
    await user.type(screen.getByLabelText('正文'), '特征值');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    await waitFor(() =>
      expect(createNote).toHaveBeenCalledWith(7, {
        content: '特征值',
        contentFormat: 'PLAIN_TEXT',
        title: '矩阵',
      })
    );
    await screen.findByText('笔记详情桩');
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.notes(7),
    });
  });

  it('treats the title as optional, the way NoteCreateRequest does', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );
    renderNotes({ api: mockApiClient({ createNote, listNotes: noNotes() }) });

    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('正文'), '只有正文');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    await waitFor(() => expect(createNote).toHaveBeenCalled());
    const body = createNote.mock.calls[0][1];
    expect(Object.keys(body)).not.toContain('title');
    expect(body.content).toBe('只有正文');
    expect(body.contentFormat).toBe(NOTE_FORM_LIMITS.defaultContentFormat);
  });

  it('shows why a submit was refused instead of looking dead', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );
    renderNotes({ api: mockApiClient({ createNote, listNotes: noNotes() }) });

    const user = userEvent.setup();
    await screen.findByRole('heading', { name: '笔记' });
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    const alerts = await screen.findByTestId('note-form-errors');
    expect(within(alerts).getByText('正文不能为空。')).toBeInTheDocument();
    expect(createNote).not.toHaveBeenCalled();
  });

  it('surfaces a failed create instead of failing quietly', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => ({
        error: { code: 'INTERNAL_ERROR', detail: 'boom' },
        response: { status: 500 },
      })
    );
    renderNotes({ api: mockApiClient({ createNote, listNotes: noNotes() }) });

    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('正文'), '特征值');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    expect(
      await screen.findByText('服务器暂时无法完成请求，请稍后重试。')
    ).toBeInTheDocument();
    expect(screen.queryByText('笔记详情桩')).not.toBeInTheDocument();
  });
});

describe('NotesPage content format gate', () => {
  it('offers exactly the formats the server accepts, and nothing else', async () => {
    renderNotes({ api: mockApiClient({ listNotes: noNotes() }) });

    const select = await screen.findByLabelText('正文格式');
    expect(
      within(select)
        .getAllByRole('option')
        .map((option) => option.getAttribute('value'))
    ).toEqual([...NOTE_CONTENT_FORMATS]);
    expect(
      within(select).queryByRole('option', { name: /HTML/i })
    ).not.toBeInTheDocument();
  });

  it('never submits a format that is not PLAIN_TEXT or MARKDOWN', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );
    renderNotes({
      api: mockApiClient({ createNote, listNotes: noNotes() }),
      url: '/spaces/7/notes?contentFormat=HTML',
    });

    const rejected = await screen.findByTestId('note-prefill-rejected');
    expect(rejected.textContent).toContain('contentFormat');
    expect(screen.getByLabelText('正文格式')).toHaveValue(
      NOTE_FORM_LIMITS.defaultContentFormat
    );

    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('正文'), '特征值');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    await waitFor(() => expect(createNote).toHaveBeenCalled());
    expect(createNote.mock.calls[0][1].contentFormat).toBe(
      NOTE_FORM_LIMITS.defaultContentFormat
    );
  });
});

describe('NotesPage W16 prefill', () => {
  it('opens a note attached to the linking entity and sends only coherent pointers', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );

    renderNotes({
      api: mockApiClient({ createNote, listNotes: noNotes() }),
      url: '/spaces/7/notes?contentBlockId=99&sourceId=3&knowledgePointId=12&contentFormat=MARKDOWN',
    });

    const summary = await screen.findByTestId('note-prefill-summary');
    expect(summary.textContent).toContain('正文片段 #99');
    expect(summary.textContent).toContain('知识点 #12');

    const user = userEvent.setup();
    expect(screen.getByRole('radio', { name: '正文片段' })).toBeChecked();
    expect(screen.getByLabelText('正文片段 ID')).toHaveValue('99');
    expect(screen.getByLabelText('知识点 ID（可选）')).toHaveValue('12');

    await user.type(screen.getByLabelText('正文'), '特征值');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    await waitFor(() => expect(createNote).toHaveBeenCalled());
    const body = createNote.mock.calls[0][1];
    // applyContext() derives the page and the source from a block, so a
    // sourceId would be overwritten — or 400 on a mismatch. The block alone
    // is the coherent body.
    expect(Object.keys(body).sort()).toEqual(
      ['content', 'contentBlockId', 'contentFormat', 'knowledgePointId'].sort()
    );
    expect(body).toEqual({
      content: '特征值',
      contentFormat: 'MARKDOWN',
      contentBlockId: 99,
      knowledgePointId: 12,
    });
  });

  it('names an unusable id in the URL instead of attaching a broken pointer', async () => {
    const createNote = vi.fn(
      async (_spaceId: number, _body: NoteCreateBody) => createdNote
    );

    renderNotes({
      api: mockApiClient({ createNote, listNotes: noNotes() }),
      url: '/spaces/7/notes?knowledgePointId=abc&questionId=5',
    });

    const rejected = await screen.findByTestId('note-prefill-rejected');
    expect(rejected.textContent).toContain('knowledgePointId');
    expect(screen.getByLabelText('知识点 ID（可选）')).toHaveValue('');
    expect(screen.getByLabelText('题目 ID（可选）')).toHaveValue('5');

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('正文'), '特征值');
    await user.click(screen.getByRole('button', { name: '创建笔记' }));

    await waitFor(() => expect(createNote).toHaveBeenCalled());
    const body = createNote.mock.calls[0][1];
    expect(Object.keys(body)).not.toContain('knowledgePointId');
    expect(body.questionId).toBe(5);
  });
});

describe('NotesPage states', () => {
  it('renders an empty state when the space has no notes', async () => {
    renderNotes({ api: mockApiClient({ listNotes: noNotes() }) });
    expect(await screen.findByText('还没有笔记')).toBeInTheDocument();
  });

  it('says which keyword found nothing when a filter is active', async () => {
    renderNotes({
      api: mockApiClient({ listNotes: noNotes() }),
      url: '/spaces/7/notes?keyword=%E4%B8%89%E8%A7%92',
    });
    expect(await screen.findByText(/没有匹配「三角」的笔记/)).toBeInTheDocument();
  });

  it('reports a failed list and recovers on retry', async () => {
    const listNotes = vi
      .fn()
      .mockResolvedValueOnce({
        error: { code: 'INTERNAL_ERROR' },
        response: { status: 500 },
      })
      .mockResolvedValue(pageResponse([noteRow(1)]));

    renderNotes({ api: mockApiClient({ listNotes }) });

    expect(
      await screen.findByText('服务器暂时无法完成请求，请稍后重试。')
    ).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '重试' }));

    await waitFor(() => expect(screen.getByText('笔记 1')).toBeInTheDocument());
    expect(listNotes).toHaveBeenCalledTimes(2);
  });

  it('links each row to its detail route and titles an untitled note by its first line', async () => {
    renderNotes({
      api: mockApiClient({
        listNotes: vi.fn(async () =>
          pageResponse([
            noteRow(5, { title: '', content: '向量空间\n下一行' }),
            noteRow(undefined, { title: '无 id 的行' }),
          ])
        ),
      }),
    });

    const link = await screen.findByRole('link', { name: '向量空间' });
    expect(link).toHaveAttribute('href', '/spaces/7/notes/5');
    expect(screen.getByText('无 id 的行')).toBeInTheDocument();
    // A row without an id has no detail route: never /notes/undefined.
    expect(document.querySelector('a[href*="undefined"]')).toBeNull();
  });
});

describe('NotesPage archived view and restore (C-Notes-archive)', () => {
  it('asks the server for the archived page when the box is checked', async () => {
    const user = userEvent.setup();
    const listNotes = vi.fn(async (_spaceId: number, _query?: NoteListQuery) =>
      pageResponse([noteRow(9, { archivedAt: '2026-10-03T09:00:00Z' })], {
        totalItems: 1,
        totalPages: 1,
      })
    );

    renderNotes({ api: mockApiClient({ listNotes }) });
    await screen.findByText('笔记 9');

    // Default view: the parameter is not sent at all, so the server's own
    // "hide archived" rule is what decides the list.
    expect(listNotes).toHaveBeenLastCalledWith(7, {
      page: 0,
      size: PAGE_SIZE,
      keyword: undefined,
      archived: undefined,
    });

    await user.click(screen.getByRole('checkbox', { name: '只看已归档' }));

    await waitFor(() =>
      expect(listNotes).toHaveBeenLastCalledWith(7, {
        page: 0,
        size: PAGE_SIZE,
        keyword: undefined,
        archived: true,
      })
    );
    expect(await screen.findByTestId('notes-archived-mode')).toBeInTheDocument();
  });

  it('restores only the row the server marked archived', async () => {
    const user = userEvent.setup();
    const restoreNote = vi.fn(async () => ({ data: undefined, response: { status: 204 } }));
    const listNotes = vi.fn(async () =>
      pageResponse([
        noteRow(9, { archivedAt: '2026-10-03T09:00:00Z' }),
        noteRow(10),
      ])
    );

    renderNotes({
      api: mockApiClient({ listNotes, restoreNote }),
      url: '/spaces/7/notes?archived=1',
    });

    await screen.findByText('笔记 9');
    expect(screen.getByTestId('note-archived-flag')).toBeInTheDocument();
    // Only the row the server marked archived is offered for restore.
    expect(screen.queryAllByRole('button', { name: '恢复' })).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: '恢复' }));
    await waitFor(() => expect(restoreNote).toHaveBeenCalledWith(7, 9));
  });

  it('reports a failed restore instead of pretending the row came back', async () => {
    const user = userEvent.setup();
    const restoreNote = vi.fn(async () => ({
      error: { code: 'NOTE_NOT_FOUND', detail: 'gone' },
      response: { status: 404 },
    }));

    renderNotes({
      api: mockApiClient({
        restoreNote,
        listNotes: vi.fn(async () =>
          pageResponse([noteRow(9, { archivedAt: '2026-10-03T09:00:00Z' })])
        ),
      }),
      url: '/spaces/7/notes?archived=1',
    });

    await user.click(await screen.findByRole('button', { name: '恢复' }));

    const alert = await screen.findByTestId('note-restore-error');
    expect(alert).toHaveTextContent('资源不存在或当前不可访问');
    expect(alert).not.toHaveTextContent('gone');
  });
});
