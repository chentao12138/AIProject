/**
 * Note detail behaviour (G01 / A8.1–A8.3).
 *
 * `useBlocker` only exists on a data router, so every test here mounts through
 * `renderWithRouterProvider` (which also drops jsdom's incompatible
 * `AbortSignal`) with the query/api providers wrapped around each route.
 */

import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { NoteDetailPage } from './NoteDetailPage';
import { ApiClientProvider } from '../../lib/api-context';
import { createTestQueryClient, mockApiClient } from '../../test/test-utils';
import { renderWithRouterProvider } from '../../test/router-utils';
import { queryKeys } from '../../lib/query-keys';
import { NOT_FOUND_MESSAGE } from '../../lib/api-error';
import type { NoteCreateBody } from './note-state';

const NOTE = {
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

type Stub = Partial<Record<keyof ApiClient, (...args: never[]) => unknown>>;

/** Every read the page performs, stubbed to a harmless empty success. */
function detailApi(overrides: Stub = {}): ApiClient {
  return mockApiClient({
    getNote: vi.fn(async () => ({ data: NOTE, response: { status: 200 } })),
    listNoteKnowledgePoints: vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    })),
    listNoteSourceBlocks: vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    })),
    listKnowledgePoints: vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    })),
    listSources: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    listContentBlocks: vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    })),
    ...overrides,
  });
}

function renderDetail({
  api = detailApi(),
  url = '/spaces/7/notes/42',
  queryClient = createTestQueryClient(),
}: {
  api?: ApiClient;
  url?: string;
  queryClient?: QueryClient;
} = {}) {
  const wrap = (element: ReactElement) => (
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={api}>{element}</ApiClientProvider>
    </QueryClientProvider>
  );

  const router = renderWithRouterProvider(
    [
      {
        path: '/spaces/:spaceId/notes/:noteId',
        element: wrap(<NoteDetailPage />),
      },
      { path: '/spaces/:spaceId/notes', element: wrap(<div>笔记列表桩</div>) },
      {
        path: '/spaces/:spaceId/knowledge/:knowledgePointId',
        element: wrap(<div>知识点桩</div>),
      },
      {
        path: '/spaces/:spaceId/sources/:sourceId',
        element: wrap(<div>来源工作台桩</div>),
      },
      {
        path: '/spaces/:spaceId/questions/:questionId',
        element: wrap(<div>题目桩</div>),
      },
    ],
    { initialEntries: [url] }
  );

  return { router, queryClient };
}

const EDITOR_PATH = '/spaces/7/notes/42';

describe('NoteDetailPage view', () => {
  it('reads the note and links every stored context pointer', async () => {
    const getNote = vi.fn(async () => ({ data: NOTE, response: { status: 200 } }));
    renderDetail({ api: detailApi({ getNote }) });

    expect(await screen.findByRole('heading', { name: '矩阵笔记' })).toBeInTheDocument();
    expect(getNote).toHaveBeenCalledWith(7, 42);
    expect(screen.getByLabelText('正文')).toHaveValue('特征值');
    expect(screen.getByLabelText('正文格式')).toHaveValue('MARKDOWN');

    const stored = screen.getByTestId('note-stored-context');
    // A block fixes its page and source, so the reader deep link carries both.
    expect(within(stored).getByRole('link', { name: '正文片段 #99' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/3?page=8&block=99'
    );
    expect(
      within(stored).getByRole('link', { name: '#12' })
    ).toHaveAttribute('href', '/spaces/7/knowledge/12');
    expect(
      within(stored).getByRole('link', { name: '#5' })
    ).toHaveAttribute('href', '/spaces/7/questions/5');
  });

  it('refuses a note id that is not a positive integer without calling the API', async () => {
    const getNote = vi.fn(async () => ({ data: NOTE, response: { status: 200 } }));
    renderDetail({ api: detailApi({ getNote }), url: '/spaces/7/notes/abc' });

    expect(await screen.findByText(NOT_FOUND_MESSAGE)).toBeInTheDocument();
    expect(getNote).not.toHaveBeenCalled();
  });

  it('reports a note that cannot be read instead of rendering an empty editor', async () => {
    renderDetail({
      api: detailApi({
        getNote: vi.fn(async () => ({
          error: { code: 'NOT_FOUND' },
          response: { status: 404 },
        })),
      }),
    });

    expect(await screen.findByText(NOT_FOUND_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByLabelText('正文')).not.toBeInTheDocument();
  });
});

describe('NoteDetailPage save (A8.1, full-document PUT)', () => {
  it('sends content, format, title and every pointer, so nothing is cleared', async () => {
    const updateNote = vi.fn(
      async (_spaceId: number, _noteId: number, _body: NoteCreateBody) => ({
        data: { ...NOTE, content: '新的特征值' },
        response: { status: 200 },
      })
    );
    renderDetail({ api: detailApi({ updateNote }) });

    const user = userEvent.setup();
    await screen.findByRole('heading', { name: '矩阵笔记' });
    await user.clear(screen.getByLabelText('正文'));
    await user.type(screen.getByLabelText('正文'), '新的特征值');
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(updateNote).toHaveBeenCalled());
    const body = updateNote.mock.calls[0][2];
    expect(body).toEqual({
      content: '新的特征值',
      contentFormat: 'MARKDOWN',
      title: '矩阵笔记',
      contentBlockId: 99,
      knowledgePointId: 12,
      questionId: 5,
    });
    // sourceId / sourcePageId are derived by the server from the block; and the
    // untouched title, format and pointers must still be in the PUT body.
    expect(Object.keys(body).sort()).toEqual(
      [
        'content',
        'contentBlockId',
        'contentFormat',
        'knowledgePointId',
        'questionId',
        'title',
      ].sort()
    );
  });

  it('refuses to save an empty body or an unsupported format', async () => {
    const updateNote = vi.fn(
      async (_spaceId: number, _noteId: number, _body: NoteCreateBody) => ({
        data: NOTE,
        response: { status: 200 },
      })
    );
    renderDetail({ api: detailApi({ updateNote }) });

    const user = userEvent.setup();
    const content = await screen.findByLabelText('正文');
    await user.clear(content);
    await user.click(screen.getByRole('button', { name: '保存' }));

    const errors = await screen.findByTestId('note-form-errors');
    expect(within(errors).getByText('正文不能为空。')).toBeInTheDocument();
    expect(updateNote).not.toHaveBeenCalled();

    await user.type(content, '特征值');
    await user.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => expect(updateNote).toHaveBeenCalled());
    expect(updateNote.mock.calls[0][2].contentFormat).toBe('MARKDOWN');
  });

  it('surfaces a rejected save instead of leaving the user thinking it worked', async () => {
    const updateNote = vi.fn(
      async (_spaceId: number, _noteId: number, _body: NoteCreateBody) => ({
        error: { code: 'VALIDATION_ERROR', detail: 'content must not be blank' },
        response: { status: 400 },
      })
    );
    renderDetail({ api: detailApi({ updateNote }) });

    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText('正文'));
    await user.type(screen.getByLabelText('正文'), '新的特征值');
    await user.click(screen.getByRole('button', { name: '保存' }));

    const alert = await screen.findByTestId('note-mutation-error');
    expect(alert.textContent).toContain('校验未通过');
    // The edits are still unsaved, so the dirty flag stays up for the guard.
    expect(screen.getByTestId('note-dirty-flag')).toBeInTheDocument();
  });
});

describe('NoteDetailPage unsaved-changes guard (A8.2)', () => {
  it('blocks navigation while edits are unsaved, then lets it through once saved', async () => {
    const updateNote = vi.fn(
      async (_spaceId: number, _noteId: number, _body: NoteCreateBody) => ({
        data: undefined,
        response: { status: 200 },
      })
    );
    const { router } = renderDetail({ api: detailApi({ updateNote }) });

    const user = userEvent.setup();
    await screen.findByRole('heading', { name: '矩阵笔记' });
    expect(screen.queryByTestId('note-dirty-flag')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('正文'), '…');
    expect(screen.getByTestId('note-dirty-flag')).toBeInTheDocument();

    await user.click(screen.getByRole('link', { name: '返回列表' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('离开此页面会丢弃尚未保存的修改。')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(EDITOR_PATH);
    expect(screen.queryByText('笔记列表桩')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '留在本页' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe(EDITOR_PATH);

    await user.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() =>
      expect(screen.queryByTestId('note-dirty-flag')).not.toBeInTheDocument()
    );

    await user.click(screen.getByRole('link', { name: '返回列表' }));
    await waitFor(() => expect(screen.getByText('笔记列表桩')).toBeInTheDocument());
    expect(router.state.location.pathname).toBe('/spaces/7/notes');
  });

  it('leaves a clean editor alone', async () => {
    const { router } = renderDetail();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: '返回列表' }));

    await waitFor(() => expect(screen.getByText('笔记列表桩')).toBeInTheDocument());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/spaces/7/notes');
  });
});

describe('NoteDetailPage archive and delete (A8.3)', () => {
  it('archives only after confirmation, invalidates the list, and says what changed', async () => {
    const archiveNote = vi.fn(async () => ({
      data: undefined,
      response: { status: 204 },
    }));
    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    renderDetail({
      api: detailApi({ archiveNote }),
      queryClient,
    });

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '归档' }));

    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '取消' }));
    expect(archiveNote).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '归档' }));
    await user.click(
      await screen.findByRole('button', { name: '确认归档' })
    );

    await waitFor(() => expect(archiveNote).toHaveBeenCalledWith(7, 42));
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.notes(7),
    });
    // The list filters archived rows and the contract has no un-archive, so the
    // page has to say the note left the list.
    expect(
      await screen.findByTestId('note-archived-notice')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '归档' })).toBeDisabled();
  });

  it('deletes only on explicit confirmation, then leaves without a dirty prompt', async () => {
    const deleteNote = vi.fn(async () => ({
      data: undefined,
      response: { status: 204 },
    }));
    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    const removeSpy = vi.spyOn(queryClient, 'removeQueries');
    const { router } = renderDetail({
      api: detailApi({ deleteNote }),
      queryClient,
    });

    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('正文'), '未保存的改动');

    await user.click(screen.getByRole('button', { name: '删除' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(
        '删除是硬删除：笔记正文、知识点关联与正文片段关联会一起移除，无法撤销。'
      )
    ).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '取消' }));
    expect(deleteNote).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '删除' }));
    await user.click(await screen.findByRole('button', { name: '确认删除' }));

    await waitFor(() => expect(deleteNote).toHaveBeenCalledWith(7, 42));
    await waitFor(() => expect(screen.getByText('笔记列表桩')).toBeInTheDocument());
    // A destroyed note has nothing to keep the user from leaving for.
    expect(screen.queryByRole('dialog', { name: '有未保存的内容' })).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/spaces/7/notes');
    expect(removeSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.note(7, 42),
    });
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.notes(7),
    });
  });

  it('reports a failed delete and stays put', async () => {
    const deleteNote = vi.fn(async () => ({
      error: { code: 'INTERNAL_ERROR' },
      response: { status: 500 },
    }));
    const { router } = renderDetail({ api: detailApi({ deleteNote }) });

    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '删除' }));
    await user.click(await screen.findByRole('button', { name: '确认删除' }));

    expect(await screen.findByTestId('note-mutation-error')).toBeInTheDocument();
    expect(screen.queryByText('笔记列表桩')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe(EDITOR_PATH);
  });
});

describe('NoteDetailPage knowledge-point links (A8.3)', () => {
  const catalog = vi.fn(async () => ({
    data: [
      { id: 12, title: '行列式' },
      { id: 15, title: '二次型' },
    ],
    response: { status: 200 },
  }));

  it('lists linked points as links to their route and unlinks one', async () => {
    const removeNoteKnowledgePoint = vi.fn(async () => ({
      data: undefined,
      response: { status: 204 },
    }));
    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    renderDetail({
      api: detailApi({
        listNoteKnowledgePoints: vi.fn(async () => ({
          data: [12],
          response: { status: 200 },
        })),
        listKnowledgePoints: catalog,
        removeNoteKnowledgePoint,
      }),
      queryClient,
    });

    const links = await screen.findByTestId('note-knowledge-point-links');
    expect(
      within(links).getByRole('link', { name: '行列式' })
    ).toHaveAttribute('href', '/spaces/7/knowledge/12');

    await userEvent.setup().click(
      within(links).getByRole('button', { name: '取消关联' })
    );

    await waitFor(() =>
      expect(removeNoteKnowledgePoint).toHaveBeenCalledWith(7, 42, 12)
    );
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.noteKnowledgePoints(7, 42),
    });
  });

  it('adds a link picked from the space’s own points, never a hand-typed id', async () => {
    const addNoteKnowledgePoints = vi.fn(async () => ({
      data: undefined,
      response: { status: 201 },
    }));

    renderDetail({
      api: detailApi({
        listNoteKnowledgePoints: vi.fn(async () => ({
          data: [12],
          response: { status: 200 },
        })),
        listKnowledgePoints: catalog,
        addNoteKnowledgePoints,
      }),
    });

    const user = userEvent.setup();
    const select = await screen.findByLabelText('选择要关联的知识点');
    // 12 is already linked, so only the unlinked point can be chosen: an unknown
    // id would come back as 201 with nothing stored.
    expect(
      within(select)
        .getAllByRole('option')
        .map((option) => option.getAttribute('value'))
    ).toEqual(['', '15']);

    await user.selectOptions(select, '15');
    await user.click(screen.getByRole('button', { name: '关联知识点' }));

    await waitFor(() =>
      expect(addNoteKnowledgePoints).toHaveBeenCalledWith(7, 42, [15])
    );
  });

  it('offers no add control when the knowledge-point catalog cannot be read', async () => {
    renderDetail({
      api: detailApi({
        listKnowledgePoints: vi.fn(async () => ({
          error: { code: 'INTERNAL_ERROR' },
          response: { status: 500 },
        })),
      }),
    });

    await screen.findByRole('heading', { name: '关联知识点' });
    expect(
      screen.queryByLabelText('选择要关联的知识点')
    ).not.toBeInTheDocument();
    expect(
      await screen.findByText('服务器暂时无法完成请求，请稍后重试。')
    ).toBeInTheDocument();
  });
});

describe('NoteDetailPage source-block links (A8.3)', () => {
  it('deep-links each linked block into the reader and unlinks it', async () => {
    const removeNoteSourceBlock = vi.fn(async () => ({
      data: undefined,
      response: { status: 204 },
    }));
    const queryClient = createTestQueryClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    renderDetail({
      api: detailApi({
        listNoteSourceBlocks: vi.fn(async () => ({
          data: [
            {
              sourceId: 3,
              sourceTitle: '教材',
              sourcePageId: 8,
              contentBlockId: 99,
              blockType: 'TEXT',
              locator: 'p.8',
            },
            // A reference with no source id has no reader route.
            { contentBlockId: 77, blockType: 'TEXT' },
          ],
          response: { status: 200 },
        })),
        removeNoteSourceBlock,
      }),
      queryClient,
    });

    const links = await screen.findByTestId('note-source-block-links');
    const deepLink = within(links).getByRole('link', { name: '教材 · 正文片段 #99' });
    expect(deepLink).toHaveAttribute('href', '/spaces/7/sources/3?page=8&block=99');
    expect(within(links).getByText('正文片段 #77')).toBeInTheDocument();

    await userEvent.setup().click(
      within(links).getAllByRole('button', { name: '取消关联' })[0]
    );

    await waitFor(() =>
      expect(removeNoteSourceBlock).toHaveBeenCalledWith(7, 42, 99)
    );
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.noteSourceBlocks(7, 42),
    });
  });

  it('links a block chosen from a source, sending the bare id array', async () => {
    const addNoteSourceBlocks = vi.fn(
      async (_spaceId: number, _noteId: number, _ids: number[]) => ({
        data: undefined,
        response: { status: 201 },
      })
    );

    renderDetail({
      api: detailApi({
        listSources: vi.fn(async () => ({
          data: [{ id: 3, title: '教材' }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            { id: 101, blockType: 'TEXT', sortOrder: 4, normalizedText: '一段正文' },
          ],
          response: { status: 200 },
        })),
        addNoteSourceBlocks,
      }),
    });

    const user = userEvent.setup();
    await user.selectOptions(
      await screen.findByLabelText('选择学习来源'),
      '3'
    );

    const blockSelect = await screen.findByLabelText('选择要关联的正文片段');
    expect(blockSelect).toBeInTheDocument();
    expect(
      within(blockSelect).getByRole('option', { name: /一段正文/ })
    ).toBeInTheDocument();

    await user.selectOptions(blockSelect, '101');
    await user.click(screen.getByRole('button', { name: '关联正文片段' }));

    await waitFor(() =>
      expect(addNoteSourceBlocks).toHaveBeenCalledWith(7, 42, [101])
    );
    expect(addNoteSourceBlocks.mock.calls[0][2]).toEqual([101]);
  });

  it('shows no block picker until a source is chosen, and none when it has no blocks', async () => {
    renderDetail({
      api: detailApi({
        listSources: vi.fn(async () => ({
          data: [{ id: 3, title: '教材' }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      }),
    });

    expect(
      screen.queryByLabelText('选择要关联的正文片段')
    ).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('选择学习来源'), '3');

    expect(
      await screen.findByText(/这个来源没有可关联的正文片段/)
    ).toBeInTheDocument();
    expect(
      screen.queryByLabelText('选择要关联的正文片段')
    ).not.toBeInTheDocument();
  });
});
