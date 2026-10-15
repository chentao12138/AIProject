/**
 * QuestionEditPage (G04) — `PUT /questions/{id}` and nothing more.
 *
 * The point of this page is a wire body, so the assertions are about the body:
 *
 *  - exactly `stem` / `explanation` / `difficulty` are sent. Options and the
 *    correct answer have no update verb (`UpdateQuestionRequest` in
 *    openapi.json, and `QuestionMapper.updateDetailsByIdAndSpace` writes three
 *    columns), so a body carrying `options` would be a field the server has no
 *    place for.
 *  - all three are sent EVEN WHEN UNCHANGED, and clearing the explanation sends
 *    `''`: `QuestionService.update()` (QuestionService.java:130-133) keeps the
 *    stored value for a null field, so omitting one would silently undo the
 *    user's edit.
 *  - the caps are the UPDATE ones (5000 / 2000 / 32), not the create ones —
 *    `explanation` is 4000 on create and 2000 here.
 *  - saving invalidates both the question and the list, and lands on the detail
 *    page. A `CacheProbe` keeps those two keys active observers, so the second
 *    call can only come from an invalidation and not from a remount.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { QuestionEditPage } from './QuestionEditPage';
import { ApiClientProvider } from '../../lib/api-context';
import { unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../../test/test-utils';
import { CONTRACT_GAP_NOTICES } from '../../lib/format';
import { UPDATE_QUESTION_FIELD_LIMITS } from './question-form-state';

const EDIT_ROUTE = '/spaces/:spaceId/questions/:questionId/edit';
const DETAIL_ROUTE = '/spaces/:spaceId/questions/:questionId';

const stored = {
  id: 4,
  spaceId: 7,
  questionType: 'SINGLE_CHOICE',
  status: 'DRAFT',
  stem: '中国的首都是？',
  explanation: '常识题。',
  difficulty: 'EASY',
  options: [
    { optionKey: 'A', content: '北京', sortOrder: 0, isCorrect: true },
    { optionKey: 'B', content: '上海', sortOrder: 1, isCorrect: false },
  ],
  answer: { correctOptionKey: 'A' },
};

function editApi(overrides = {}): ApiClient {
  return mockApiClient({
    getQuestion: vi.fn(async () => ({ data: { ...stored }, response: { status: 200 } })),
    updateQuestion: vi.fn(async () => ({ data: { ...stored }, response: { status: 200 } })),
    listQuestions: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    ...overrides,
  });
}

/**
 * Renders the edit page beside the detail route, with an observer on both
 * cache keys the save must invalidate.
 */
function renderEdit(api: ApiClient) {
  function CacheProbe() {
    useQuery({
      queryKey: queryKeys.questions(7),
      queryFn: () => api.listQuestions(7).then(unwrap),
    });
    useQuery({
      queryKey: queryKeys.question(7, 4),
      queryFn: () => api.getQuestion(7, 4).then(unwrap),
    });
    return null;
  }

  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <ApiClientProvider client={api}>
        <MemoryRouter initialEntries={['/spaces/7/questions/4/edit']}>
          <Routes>
            <Route path={EDIT_ROUTE} element={<QuestionEditPage />} />
            <Route path={DETAIL_ROUTE} element={<div>题目 #4 详情</div>} />
          </Routes>
          <CacheProbe />
        </MemoryRouter>
      </ApiClientProvider>
    </QueryClientProvider>
  );
}

function sentBody(api: ApiClient) {
  const calls = vi.mocked(api.updateQuestion).mock.calls;
  expect(calls).toHaveLength(1);
  return calls[0][2];
}

describe('QuestionEditPage — the editable subset only', () => {
  it('prefills the stored stem, explanation and difficulty', async () => {
    const api = editApi();
    renderEdit(api);

    expect(await screen.findByLabelText('题干')).toHaveValue('中国的首都是？');
    expect(screen.getByLabelText('解析')).toHaveValue('常识题。');
    // `difficulty` now lives in the custom dropdown: the stored EASY shows as
    // its label, and the trigger button is the control that carries it.
    expect(screen.getByRole('button', { name: /难度/ })).toHaveTextContent('简单');
  });

  it('sends exactly stem / explanation / difficulty, with no answer fields', async () => {
    const user = userEvent.setup();
    const api = editApi();
    renderEdit(api);

    const stemInput = await screen.findByLabelText('题干');
    await user.clear(stemInput);
    await user.type(stemInput, '中国的首都是哪座城市？');

    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() =>
      expect(api.updateQuestion).toHaveBeenCalledWith(
        7,
        4,
        expect.objectContaining({ stem: '中国的首都是哪座城市？' })
      )
    );
    // Unchanged fields are still sent: the server keeps the stored value for a
    // null field, so omission is not a no-op — it is a different instruction.
    expect(sentBody(api)).toEqual({
      stem: '中国的首都是哪座城市？',
      explanation: '常识题。',
      difficulty: 'EASY',
    });
    expect(Object.keys(sentBody(api)).sort()).toEqual([
      'difficulty',
      'explanation',
      'stem',
    ]);
  });

  it('sends an emptied explanation as an empty string so the clear sticks', async () => {
    const user = userEvent.setup();
    const api = editApi();
    renderEdit(api);

    await user.clear(await screen.findByLabelText('解析'));
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(api.updateQuestion).toHaveBeenCalled());
    expect(sentBody(api)).toEqual({
      stem: '中国的首都是？',
      explanation: '',
      difficulty: 'EASY',
    });
  });

  it('applies the update caps, which are narrower than the create ones', async () => {
    renderEdit(editApi());

    expect(await screen.findByLabelText('题干')).toHaveAttribute(
      'maxLength',
      String(UPDATE_QUESTION_FIELD_LIMITS.stem)
    );
    expect(screen.getByLabelText('解析')).toHaveAttribute(
      'maxLength',
      String(UPDATE_QUESTION_FIELD_LIMITS.explanation)
    );
    expect(UPDATE_QUESTION_FIELD_LIMITS.explanation).toBeLessThan(4000);
  });

  it('blocks an empty stem with a visible reason and never calls the server', async () => {
    const user = userEvent.setup();
    const api = editApi();
    renderEdit(api);

    await user.clear(await screen.findByLabelText('题干'));
    await user.click(screen.getByRole('button', { name: '保存' }));

    // `UpdateQuestionRequest.stem` has no @NotBlank, so the server would store
    // an empty stem happily — this guard is the only one that exists.
    expect(await screen.findByText('题干不能为空。')).toBeInTheDocument();
    expect(api.updateQuestion).not.toHaveBeenCalled();
  });

  it('surfaces a rejected save instead of leaving the form looking saved', async () => {
    const user = userEvent.setup();
    const api = editApi({
      updateQuestion: vi.fn(async () => ({
        error: { code: 'BAD_REQUEST', detail: 'explanation must be at most 2000 characters' },
        response: { status: 400 },
      })),
    });
    renderEdit(api);

    await user.clear(await screen.findByLabelText('解析'));
    await user.type(screen.getByLabelText('解析'), 'x');
    await user.click(screen.getByRole('button', { name: '保存' }));

    expect(await screen.findByTestId('edit-mutation-error')).toHaveTextContent(
      '校验未通过：explanation must be at most 2000 characters'
    );
    // The typed value survives the failure: the user must not retype it.
    expect(screen.getByLabelText('解析')).toHaveValue('x');
  });
});

describe('QuestionEditPage — what is locked, and why', () => {
  it('names the contract reason beside the read-only options', async () => {
    renderEdit(editApi());

    expect(await screen.findByTestId('update-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.QUESTION_UPDATE_FIELDS
    );
    expect(screen.getByTestId('locked-fields')).toHaveTextContent(
      '选项与正确答案无法修改'
    );
    // The options are shown, because "locked" has to mean something the user
    // can see — but they are shown, not edited.
    const locked = await screen.findAllByTestId('locked-option');
    expect(locked.map((node) => node.textContent)).toEqual([
      'A. 北京（正确）',
      'B. 上海',
    ]);
    expect(screen.getByLabelText('题型')).toHaveAttribute('readonly');
    expect(screen.getByLabelText('题型')).toBeDisabled();
  });

  // The contract's PUT description allows a DRAFT only and answers 409
  // `QUESTION_NOT_EDITABLE` for anything else, so no state but DRAFT gets a
  // saveable form.
  it.each(['ARCHIVED', 'PUBLISHED', 'REJECTED'])(
    'refuses to save a %s question and says why',
    async (status) => {
      const user = userEvent.setup();
      const api = editApi({
        getQuestion: vi.fn(async () => ({
          data: { ...stored, status },
          response: { status: 200 },
        })),
      });
      renderEdit(api);

      const banner = await screen.findByTestId('not-editable-banner');
      expect(banner).toHaveTextContent(status);
      expect(banner).toHaveTextContent('QUESTION_NOT_EDITABLE');
      expect(screen.getByTestId('edit-save-button')).toBeDisabled();
      expect(screen.getByLabelText('题干')).toBeDisabled();

      // Typing cannot re-enable a doomed save.
      await user.type(screen.getByLabelText('题干'), '？');
      expect(api.updateQuestion).not.toHaveBeenCalled();
    }
  );

  it('names the 409 code and re-reads the question when it changes state', async () => {
    const user = userEvent.setup();
    // First read says DRAFT (so the form opens editable); the server has since
    // published it, and the second read is what the recovery asks for.
    let reads = 0;
    const getQuestion = vi.fn(async () => {
      reads += 1;
      return {
        data: { ...stored, status: reads === 1 ? 'DRAFT' : 'PUBLISHED' },
        response: { status: 200 },
      };
    });
    const api = editApi({
      getQuestion,
      updateQuestion: vi.fn(async () => ({
        error: { code: 'QUESTION_NOT_EDITABLE', detail: 'the question is not a DRAFT' },
        response: { status: 409 },
      })),
    });
    renderEdit(api);

    await screen.findByLabelText('题干');
    await user.type(screen.getByLabelText('题干'), '再改一点');
    await user.click(screen.getByTestId('edit-save-button'));

    const error = await screen.findByTestId('edit-mutation-error');
    expect(error).toHaveTextContent('已经不是草稿');
    // The server's English reason never reaches the screen.
    expect(error).not.toHaveTextContent('not a DRAFT');

    // Recovery: the page re-reads, and the now-published document turns the
    // editable form into the refused one — with the draft edits dropped, because
    // continuing to offer them (or 有未保存的修改) would be a lie.
    const banner = await screen.findByTestId('not-editable-banner');
    expect(banner).toHaveTextContent('PUBLISHED');
    await waitFor(() => expect(getQuestion).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('edit-save-button')).toBeDisabled();
    expect(screen.queryByTestId('edit-dirty-flag')).not.toBeInTheDocument();
    // The typed text is still on screen: refusing the save is not discarding it.
    expect(screen.getByLabelText('题干')).toHaveValue('中国的首都是？再改一点');
  });

  it('marks the form dirty only after a real change', async () => {
    const user = userEvent.setup();
    renderEdit(editApi());

    const stemInput = await screen.findByLabelText('题干');
    expect(screen.queryByTestId('edit-dirty-flag')).not.toBeInTheDocument();

    await user.type(stemInput, '呢');
    expect(screen.getByTestId('edit-dirty-flag')).toHaveTextContent('有未保存的修改');

    await user.clear(stemInput);
    await user.type(stemInput, '中国的首都是？');
    expect(screen.queryByTestId('edit-dirty-flag')).not.toBeInTheDocument();
  });
});

describe('QuestionEditPage — a save updates the cache and leaves the form', () => {
  it('invalidates the question and the list, then shows the detail page', async () => {
    const user = userEvent.setup();
    const getQuestion = vi.fn(async () => ({
      data: { ...stored },
      response: { status: 200 },
    }));
    const listQuestions = vi.fn(async () => ({ data: [], response: { status: 200 } }));
    const api = editApi({ getQuestion, listQuestions });
    renderEdit(api);

    await user.clear(await screen.findByLabelText('题干'));
    await user.type(screen.getByLabelText('题干'), '改过的题干');
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(api.updateQuestion).toHaveBeenCalled());

    // Both keys have a live observer in this render, so a second call can only
    // be an invalidation: the detail row and the list stem are both stale now.
    await waitFor(() => expect(getQuestion).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(listQuestions).toHaveBeenCalledTimes(2));

    expect(await screen.findByText('题目 #4 详情')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '保存' })).not.toBeInTheDocument();
  });

  it('keeps the create route usable: the form is a page, not a duplicate poster', async () => {
    const user = userEvent.setup();
    const api = editApi({ createQuestion: vi.fn(async () => ({ data: { id: 9 }, response: { status: 201 } })) });
    renderWithProviders(<QuestionEditPage />, {
      apiClient: api,
      initialEntries: ['/spaces/7/questions/4/edit'],
      routePath: EDIT_ROUTE,
    });

    await user.clear(await screen.findByLabelText('题干'));
    await user.type(screen.getByLabelText('题干'), '改过的题干');
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(api.updateQuestion).toHaveBeenCalled());
    expect(api.createQuestion).not.toHaveBeenCalled();
  });
});
