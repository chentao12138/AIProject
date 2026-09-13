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
    expect(screen.getAllByText('DRAFT')).toHaveLength(1);
    expect(screen.getAllByText('PUBLISHED')).toHaveLength(1);
  });

  it('shows empty state when no points exist', async () => {
    renderKnowledge(baseApi({}));
    expect(
      await screen.findByText('No knowledge points yet')
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
      await screen.findByRole('button', { name: 'Create Category' })
    );
    const submit = screen.getByRole('button', { name: 'Create' });
    expect(submit).toBeDisabled();
    await user.click(submit);
    expect(createKnowledgeCategory).not.toHaveBeenCalled();
  });

  it('offers only current-space categories plus a root option (PHASE 13)', async () => {
    const user = userEvent.setup();
    renderKnowledge(baseApi({}));

    await user.click(
      await screen.findByRole('button', { name: 'Create Category' })
    );

    const select = screen.getByLabelText('Parent category (optional)');
    const options = Array.from(select.querySelectorAll('option')).map(
      (option) => ({ value: option.value, text: option.textContent })
    );
    expect(options[0]).toEqual({ value: '', text: '(none — root category)' });
    expect(options.map((o) => o.value)).toEqual(['', '10', '11']);
    // never an "undefined" option value
    expect(options.some((o) => o.value === 'undefined')).toBe(false);
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
      await screen.findByRole('button', { name: 'Create Category' })
    );
    const select = screen.getByLabelText('Parent category (optional)');
    const optionTexts = Array.from(select.querySelectorAll('option')).map(
      (option) => option.textContent
    );
    expect(optionTexts).toEqual(['(none — root category)', 'Algebra']);
    expect(optionTexts.join()).not.toContain('Zero id');
    expect(optionTexts.join()).not.toContain('Ghost');
    expect(optionTexts.join()).not.toContain('Negative');
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
      (await screen.findAllByRole('button', { name: 'Create Knowledge Point' }))[0]
    );
    const select = screen.getByLabelText('Category (optional)');
    const optionTexts = Array.from(select.querySelectorAll('option')).map(
      (option) => option.textContent
    );
    expect(optionTexts).toEqual(['(none)', 'Algebra']);
    expect(optionTexts.join()).not.toContain('Zero id');
  });

  it('submits a strict payload with parsed optional integers (PHASE 13)', async () => {
    const user = userEvent.setup();
    const createKnowledgeCategory = vi.fn(async () => ({
      data: { id: 30, name: 'Trigonometry' },
      response: { status: 201 },
    }));
    renderKnowledge(baseApi({ createKnowledgeCategory }));

    await user.click(
      await screen.findByRole('button', { name: 'Create Category' })
    );

    await user.type(screen.getByLabelText('Name'), '  Trigonometry  ');
    await user.selectOptions(
      screen.getByLabelText('Parent category (optional)'),
      '10'
    );
    // hex-looking input must NOT coerce to 16; whitespace must NOT become 0
    // (fireEvent bypasses user-event's number-input char filtering so the
    // strict parser is what decides)
    fireEvent.change(screen.getByLabelText('Sort order (optional)'), {
      target: { value: '0x10' },
    });
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      await screen.findByRole('button', { name: 'Create Category' })
    );
    await user.type(screen.getByLabelText('Name'), 'X');
    await user.type(screen.getByLabelText('Sort order (optional)'), '-3');
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      await screen.findByRole('button', { name: 'Create Category' })
    );
    await user.type(screen.getByLabelText('Name'), 'Trigonometry');
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      await screen.findByRole('button', { name: 'Create Category' })
    );
    await user.type(screen.getByLabelText('Name'), 'Trigonometry');
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      name: 'Create Knowledge Point',
    });
    await user.click(pointButtons[0]);

    const submit = screen.getByRole('button', { name: 'Create' });
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('Title'), 'Only title');
    expect(submit).toBeDisabled();

    await user.clear(screen.getByLabelText('Title'));
    await user.type(screen.getByLabelText('Content'), 'Only content');
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
      name: 'Create Knowledge Point',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('Title'), '  My Point  ');
    await user.type(screen.getByLabelText('Summary (optional)'), '   ');
    await user.type(
      screen.getByLabelText('Content'),
      '  line one{enter}{enter}line two  '
    );
    await user.selectOptions(
      screen.getByLabelText('Category (optional)'),
      '11'
    );
    await user.type(
      screen.getByLabelText('Difficulty (optional free text)'),
      '  intermediate  '
    );
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      name: 'Create Knowledge Point',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('Title'), 'My Point');
    await user.type(screen.getByLabelText('Content'), 'Body');
    await user.click(screen.getByRole('button', { name: 'Create' }));

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
      name: 'Create Knowledge Point',
    });
    await user.click(pointButtons[0]);

    await user.type(screen.getByLabelText('Title'), 'My Point');
    await user.type(screen.getByLabelText('Content'), 'Body');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
