/**
 * KnowledgePage tests (K1.8 + FE-001.5 PHASE 13/14/15).
 *
 * PHASE 13 — category create UX: blank validation, space-scoped parent
 *   options, strict optional-int parsing, pending/error/success.
 * PHASE 14 — point list hardening: safe optionals, category lookup,
 *   invalid-id static rows.
 * PHASE 15 — point create: blank title/content rejection, trimmed
 *   payload with preserved internal content whitespace, invalidation.
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { KnowledgePage } from './KnowledgePage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const baseApi = (overrides: Record<string, unknown>) => {
  const apiClient = mockApiClient({
    listKnowledgeCategories: vi.fn(async () => ({
      data: [{ id: 10, name: 'Algebra' }, { id: 11, name: 'Geometry' }],
      response: { status: 200 },
    })),
    listKnowledgePoints: vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    })),
    ...overrides,
  });
  return apiClient;
};

function renderKnowledge(apiClient: ReturnType<typeof mockApiClient>) {
  return renderWithProviders(<KnowledgePage />, {
    apiClient,
    initialEntries: ['/spaces/7/knowledge'],
    routePath: '/spaces/:spaceId/knowledge',
  });
}

/**
 * The option rows of the SelectMenu that is currently open.
 *
 * SelectMenu only renders its listbox while the trigger is expanded, so a test
 * has to open it first. `textContent` also carries the `✓` that marks the
 * current value (it is `aria-hidden`, so it is not part of the accessible name)
 * — stripped here to keep the comparison about the labels themselves.
 */
function openOptionLabels(): string[] {
  return screen
    .getAllByRole('option')
    .map((option) => option.textContent?.replace('✓', '').trim() ?? '');
}

describe('KnowledgePage — list rendering', () => {
  it('renders DRAFT and PUBLISHED status badges (K1.8)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [{ id: 10, name: 'Algebra' }],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [
          {
            id: 1,
            title: 'Draft point',
            status: 'DRAFT',
            originType: 'USER_CURATED',
          },
          {
            id: 2,
            title: 'Published point',
            status: 'PUBLISHED',
            originType: 'USER_CURATED',
            publishedAt: '2026-09-01T10:00:00Z',
          },
        ],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText('Draft point')).toBeInTheDocument();
    expect(screen.getByText('Published point')).toBeInTheDocument();
    expect(screen.getAllByText('草稿')).toHaveLength(1);
    expect(screen.getAllByText('已发布')).toHaveLength(1);
  });

  it('shows empty state when no points exist', async () => {
    renderKnowledge(baseApi({}));
    expect(
      await screen.findByText('还没有知识点')
    ).toBeInTheDocument();
  });

  it('renders points with missing optional fields safely (PHASE 14)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [
          {
            id: 1,
            title: 'Bare point',
            status: 'DRAFT',
            // no summary, no difficulty, no categoryId, no originType
          },
        ],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText('Bare point')).toBeInTheDocument();
    // no undefined/null artifacts
    expect(document.body.textContent).not.toMatch(/#undefined|#null/);
  });

  it('resolves category names via the category map (PHASE 14)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [{ id: 10, name: 'Algebra' }],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [{ id: 1, title: 'Point A', status: 'DRAFT', categoryId: 10 }],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText('Point A')).toBeInTheDocument();
    expect(screen.getByText('Algebra')).toBeInTheDocument();
  });

  it('renders multiple points in backend order (PHASE 14)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [
          { id: 1, title: 'First', status: 'DRAFT' },
          { id: 2, title: 'Second', status: 'DRAFT' },
        ],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    const titles = await screen.findAllByText(/First|Second/);
    expect(titles.map((el) => el.textContent)).toEqual(['First', 'Second']);
  });

  it('renders a point without a valid id as a static row (PHASE 14)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [{ id: undefined, title: 'Ghost', status: 'DRAFT' }],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText('Ghost')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Ghost/ })
    ).not.toBeInTheDocument();
  });

  it('renders very long titles/summaries and deep category names (STRETCH C)', async () => {
    const longTitle = 'T'.repeat(300);
    const longSummary = 'S'.repeat(500);
    const longCategoryName = 'C'.repeat(200);
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [
          { id: 1, name: longCategoryName },
          { id: 2, name: 'Child', parentId: 1 },
          { id: 3, name: 'Grandchild', parentId: 2 },
        ],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [
          {
            id: 1,
            title: longTitle,
            summary: longSummary,
            status: 'DRAFT',
            categoryId: 3,
            difficulty: 'D'.repeat(100),
          },
        ],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText(longTitle)).toBeInTheDocument();
    expect(screen.getByText(longSummary)).toBeInTheDocument();
    // tree labels carry a "· " prefix — match the name fragment
    expect(screen.getByText(new RegExp(`C{20}`))).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/#undefined|#null/);
  });

  it('renders a deep category chain without crashing (STRETCH C)', async () => {
    const depth = 40;
    const categories = Array.from({ length: depth }, (_, i) => ({
      id: i + 1,
      name: `Level ${i + 1}`,
      parentId: i === 0 ? undefined : i,
    }));
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: categories,
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderKnowledge(apiClient);

    expect(await screen.findByText(/Level 1$/)).toBeInTheDocument();
    expect(screen.getByText(/Level 40$/)).toBeInTheDocument();
  });
});

describe('KnowledgePage — category create (PHASE 13)', () => {
  it('disables submit for a blank category name', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'X' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );
    const submit = screen.getByRole('button', { name: '创建' });
    expect(submit).toBeDisabled();
    await user.click(submit);
    expect(createKnowledgeCategory).not.toHaveBeenCalled();
  });

  it('offers only current-space categories plus a root option (PHASE 13)', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'Child of Geometry' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );

    // The trigger's accessible name is `label + current value`, so match the
    // label only; the listbox exists only after the click below.
    await user.click(
      await screen.findByRole('button', { name: /父分类（可选）/ })
    );
    expect(openOptionLabels()).toEqual([
      '(none — root category)',
      'Algebra',
      'Geometry',
    ]);
    // never an "undefined" entry in the list
    expect(openOptionLabels().join()).not.toContain('undefined');

    // SelectMenu does not put its option values in the DOM, so the id behind a
    // label is proven on the wire: Geometry is category 11.
    await user.click(screen.getByRole('option', { name: 'Geometry' }));
    expect(
      screen.getByRole('button', { name: /父分类（可选）/ })
    ).toHaveTextContent('Geometry');

    await user.type(screen.getByLabelText('名称'), 'Child of Geometry');
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(createKnowledgeCategory).toHaveBeenCalledWith(7, {
        name: 'Child of Geometry',
        description: undefined,
        parentId: 11,
        sortOrder: undefined,
      });
    });
  });

  it('excludes malformed category ids from the parent selector (FIX-01)', async () => {
    const user = userEvent.setup();
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [
          { id: 10, name: 'Algebra' },
          { id: 0, name: 'Zero id' },
          { id: undefined, name: 'Ghost' },
          { id: -3, name: 'Negative' },
        ],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });
    renderKnowledge(apiClient);

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );
    await user.click(
      await screen.findByRole('button', { name: /父分类（可选）/ })
    );
    expect(openOptionLabels()).toEqual(['(none — root category)', 'Algebra']);
    // The rows still render in the tree, so the exclusion is asserted against
    // the option list itself, not against the document.
    expect(screen.queryByRole('option', { name: 'Zero id' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Ghost' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Negative' })).not.toBeInTheDocument();
  });

  it('excludes malformed category ids from the point category selector (FIX-01)', async () => {
    const user = userEvent.setup();
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [
          { id: 10, name: 'Algebra' },
          { id: 0, name: 'Zero id' },
        ],
        response: { status: 200 },
      })),
      listKnowledgePoints: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });
    renderKnowledge(apiClient);

    await user.click(
      (await screen.findAllByRole('button', { name: '创建知识点' }))[0]
    );
    await user.click(
      await screen.findByRole('button', { name: /^分类（可选）/ })
    );
    expect(openOptionLabels()).toEqual(['(none)', 'Algebra']);
    expect(screen.queryByRole('option', { name: 'Zero id' })).not.toBeInTheDocument();
  });

  it('submits a strict payload with parsed optional integers (PHASE 13)', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'Trigonometry' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );

    await user.type(screen.getByLabelText('名称'), '  Trigonometry  ');
    await user.click(
      await screen.findByRole('button', { name: /父分类（可选）/ })
    );
    // Algebra is category 10: the click below is what puts that id on the wire.
    await user.click(screen.getByRole('option', { name: 'Algebra' }));
    // hex-looking input must NOT coerce to 16; whitespace must NOT become 0
    // (fireEvent bypasses user-event's number-input char filtering so the
    // strict parser is what decides)
    fireEvent.change(screen.getByLabelText('排序（可选）'), {
      target: { value: '0x10' },
    });
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(createKnowledgeCategory).toHaveBeenCalledWith(7, {
        name: 'Trigonometry',
        description: undefined,
        parentId: 10,
        sortOrder: undefined,
      });
    });
  });

  it('submits negative sort orders as-is (PHASE 13)', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'X' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );
    await user.type(screen.getByLabelText('名称'), 'X');
    await user.type(screen.getByLabelText('排序（可选）'), '-3');
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(createKnowledgeCategory).toHaveBeenCalledWith(7, {
        name: 'X',
        description: undefined,
        parentId: undefined,
        sortOrder: -3,
      });
    });
  });

  it('shows a create error inside the dialog and keeps it open (PHASE 13)', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );
    await user.type(screen.getByLabelText('名称'), 'Trigonometry');
    await user.click(screen.getByRole('button', { name: '创建' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('closes the dialog and refetches categories on success (PHASE 13)', async () => {
    const user = userEvent.setup();
    const listKnowledgeCategories = vi
      .fn()
      .mockResolvedValueOnce({
        data: [{ id: 10, name: 'Algebra' }],
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: [
          { id: 10, name: 'Algebra' },
          { id: 30, name: 'Trigonometry' },
        ],
        response: { status: 200 },
      });
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'Trigonometry' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ listKnowledgeCategories, createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: '创建分类' })
    );
    await user.type(screen.getByLabelText('名称'), 'Trigonometry');
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(listKnowledgeCategories).toHaveBeenCalledTimes(2);
    expect(await screen.findByText(/Trigonometry/)).toBeInTheDocument();
  });
});

describe('KnowledgePage — point create (PHASE 15)', () => {
  it('disables submit for blank title or content', async () => {
    const user = userEvent.setup();
    renderKnowledge(baseApi({}));

    const pointButtons = await screen.findAllByRole('button', {
      name: '创建知识点',
    });
    await user.click(pointButtons[0]);

    const submit = screen.getByRole('button', { name: '创建' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('标题'), 'Only title');
    expect(submit).toBeDisabled();

    await user.clear(screen.getByLabelText('标题'));
    await user.type(screen.getByLabelText('正文'), 'Only content');
    expect(submit).toBeDisabled();
  });

  it('submits a trimmed payload preserving internal content whitespace (PHASE 15)', async () => {
    const user = userEvent.setup();
    const createKnowledgePoint = vi.fn(async () => ({
      data: { id: 40, title: 'Point' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgePoint }));

    const pointButtons = await screen.findAllByRole('button', {
      name: '创建知识点',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('标题'), '  My Point  ');
    await user.type(screen.getByLabelText('摘要（可选）'), '   ');
    await user.type(
      screen.getByLabelText('正文'),
      '  line one{enter}{enter}line two  '
    );
    await user.click(
      await screen.findByRole('button', { name: /^分类（可选）/ })
    );
    // Geometry is category 11 — the option click is what puts the id on the wire.
    await user.click(screen.getByRole('option', { name: 'Geometry' }));
    await user.type(
      screen.getByLabelText('难度（可选，自由填写）'),
      '  intermediate  '
    );
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(createKnowledgePoint).toHaveBeenCalledWith(7, {
        title: 'My Point',
        summary: undefined,
        content: 'line one\n\nline two',
        categoryId: 11,
        difficulty: 'intermediate',
      });
    });
  });

  it('closes the dialog and refetches the list on success (PHASE 15)', async () => {
    const user = userEvent.setup();
    const listKnowledgePoints = vi
      .fn()
      .mockResolvedValueOnce({ data: [], response: { status: 200 } })
      .mockResolvedValue({
        data: [{ id: 40, title: 'My Point', status: 'DRAFT' }],
        response: { status: 200 },
      });
    const createKnowledgePoint = vi.fn(async () => ({
      data: { id: 40, title: 'My Point' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ listKnowledgePoints, createKnowledgePoint }));

    const pointButtons = await screen.findAllByRole('button', {
      name: '创建知识点',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('标题'), 'My Point');
    await user.type(screen.getByLabelText('正文'), 'Body');
    await user.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(listKnowledgePoints).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('My Point')).toBeInTheDocument();
  });

  it('shows a create error inside the dialog (PHASE 15)', async () => {
    const user = userEvent.setup();
    const createKnowledgePoint = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    renderKnowledge(baseApi({ createKnowledgePoint }));

    const pointButtons = await screen.findAllByRole('button', {
      name: '创建知识点',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('标题'), 'My Point');
    await user.type(screen.getByLabelText('正文'), 'Body');
    await user.click(screen.getByRole('button', { name: '创建' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
