/**
 * QuestionDetailPage — the review leg (G02) plus the verbs the contract has
 * (G04 edit, G09 archive / restore).
 *
 * The detail page is where a question is read back, so it is where "did the
 * author actually record a correct answer?" becomes visible:
 *
 *  - FILL_BLANK shows its reference answer (the loop closes).
 *  - ORDERING / MATCHING are publishable now — the create side carries
 *    correctOrder / matches and the answer side carries orderingAnswer /
 *    matchingAnswer, so publishing is not a lie. What is STILL one-way is the
 *    read of the stored answer: QuestionAnswerView has no such field, so the
 *    review shows the gap notice instead of a fabricated correct order.
 *  - Edit is a real link (PUT exists, over exactly stem/explanation/difficulty),
 *    archive is a real action with a confirmation, restore is a real action —
 *    and delete does not exist at all, so no delete control appears.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionDetailPage } from './QuestionDetailPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import { CONTRACT_GAP_NOTICES } from '../../lib/format';

const ROUTE = '/spaces/:spaceId/questions/:questionId';

function renderDetail(question: Record<string, unknown>, overrides = {}) {
  const api = mockApiClient({
    getQuestion: vi.fn(async () => ({
      data: { id: 4, status: 'DRAFT', ...question },
      response: { status: 200 },
    })),
    publishQuestion: vi.fn(async () => ({
      data: { id: 4, status: 'PUBLISHED' },
      response: { status: 200 },
    })),
    archiveQuestion: vi.fn(async () => ({
      data: { id: 4, status: 'ARCHIVED' },
      response: { status: 200 },
    })),
    restoreQuestion: vi.fn(async () => ({
      data: { id: 4, status: 'DRAFT' },
      response: { status: 200 },
    })),
    ...overrides,
  });
  renderWithProviders(<QuestionDetailPage />, {
    apiClient: api,
    initialEntries: ['/spaces/7/questions/4'],
    routePath: ROUTE,
  });
  return api;
}

const choiceOptions = [
  { optionKey: 'A', content: '第一项' },
  { optionKey: 'B', content: '第二项' },
];

describe('QuestionDetailPage — practice entry per knowledge point (W16)', () => {
  it('hands its knowledge point id to the practice start page', async () => {
    renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '题干',
      knowledgePointIds: [12],
    });

    const link = await screen.findByRole('link', { name: '练习该知识点' });
    expect(link).toHaveAttribute('href', '/spaces/7/practice?knowledgePointId=12');
  });
});

describe('QuestionDetailPage — FILL_BLANK review (G02)', () => {
  it('shows the reference answer the author stored', async () => {
    renderDetail({
      questionType: 'FILL_BLANK',
      stem: '水的化学式是 ___。',
      answer: { referenceAnswer: 'H2O' },
    });

    expect(await screen.findByTestId('reference-answer')).toHaveTextContent('H2O');
    expect(screen.queryByTestId('publish-gap-notice')).not.toBeInTheDocument();
  });

  it('still publishes a draft it can grade', async () => {
    const user = userEvent.setup();
    const api = renderDetail({
      questionType: 'FILL_BLANK',
      stem: '水的化学式是 ___。',
      answer: { referenceAnswer: 'H2O' },
    });

    await user.click(await screen.findByRole('button', { name: '发布' }));
    await waitFor(() => expect(api.publishQuestion).toHaveBeenCalledWith(7, 4));
  });
});

describe('QuestionDetailPage — the two new types are publishable, still one-way to read', () => {
  it('publishes an ORDERING draft and keeps the review gap honest', async () => {
    const user = userEvent.setup();
    const api = renderDetail({
      questionType: 'ORDERING',
      stem: '把下面的步骤排序。',
      options: choiceOptions,
      answer: {},
    });

    // Create carries correctOrder and the answer payload carries orderingAnswer,
    // so publishing produces a gradeable item — the withheld button was a
    // property of the STALE contract snapshot, not of this one.
    await user.click(await screen.findByRole('button', { name: '发布' }));
    await waitFor(() => expect(api.publishQuestion).toHaveBeenCalledWith(7, 4));
    expect(screen.queryByTestId('publish-gap-notice')).not.toBeInTheDocument();

    // What is still missing is the READ side: QuestionAnswerView has no
    // correctOrder, so the stored order cannot be shown. The review surface
    // says exactly that instead of inventing one.
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.ORDERING_REVIEW
    );
  });

  it('publishes a MATCHING draft and renders the pair set as unreadable', async () => {
    const user = userEvent.setup();
    const api = renderDetail({
      questionType: 'MATCHING',
      stem: '把概念与定义配对。',
      options: choiceOptions,
      answer: {},
    });

    await user.click(await screen.findByRole('button', { name: '发布' }));
    await waitFor(() => expect(api.publishQuestion).toHaveBeenCalledWith(7, 4));

    expect(screen.getByTestId('matching-readonly')).toBeInTheDocument();
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.MATCHING_REVIEW
    );
  });

  it('never shows a publish button for a published question', async () => {
    renderDetail(
      { questionType: 'ORDERING', stem: '排序题干', options: choiceOptions },
      {
        getQuestion: vi.fn(async () => ({
          data: {
            id: 4,
            questionType: 'ORDERING',
            status: 'PUBLISHED',
            stem: '排序题干',
            options: choiceOptions,
          },
          response: { status: 200 },
        })),
      }
    );

    await screen.findByTestId('contract-gap-notice');
    expect(screen.queryByRole('button', { name: '发布' })).not.toBeInTheDocument();
  });
});

describe('QuestionDetailPage — edit is a real route (G04)', () => {
  it('links to the edit form and states what the PUT cannot change', async () => {
    renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '单选题干',
      options: choiceOptions,
      answer: { correctOptionKey: 'A' },
    });

    const link = await screen.findByTestId('question-edit-link');
    expect(link).toHaveAttribute('href', '/spaces/7/questions/4/edit');
    expect(screen.getByTestId('update-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.QUESTION_UPDATE_FIELDS
    );
    // There is still no delete verb anywhere in the contract.
    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument();
  });

  it('hides the edit link for an archived question, which PUT refuses', async () => {
    renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '单选题干',
      status: 'ARCHIVED',
      options: choiceOptions,
    });

    await screen.findByTestId('question-restore-button');
    expect(screen.queryByTestId('question-edit-link')).not.toBeInTheDocument();
  });

  it('marks the correct option in review', async () => {
    renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '单选题干',
      options: [
        { optionKey: 'A', content: '第一项', isCorrect: true },
        { optionKey: 'B', content: '第二项', isCorrect: false },
      ],
      answer: { correctOptionKey: 'A' },
    });

    expect(await screen.findByRole('button', { name: '第一项' })).toBeDisabled();
  });
});

describe('QuestionDetailPage — archive and restore (G09)', () => {
  it('asks before archiving, and a cancelled dialog sends nothing', async () => {
    const user = userEvent.setup();
    const api = renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '单选题干',
      options: choiceOptions,
      answer: { correctOptionKey: 'A' },
    });

    await user.click(await screen.findByTestId('question-archive-button'));
    const dialog = await screen.findByRole('dialog');
    // Honest copy: it is not a delete, and restore does not come back published.
    expect(dialog).toHaveTextContent('不是删除');
    expect(dialog).toHaveTextContent('草稿');

    await user.click(screen.getByRole('button', { name: '取消' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(api.archiveQuestion).not.toHaveBeenCalled();
  });

  it('archives on confirm, invalidates the detail and the list, and flips to 恢复', async () => {
    const user = userEvent.setup();
    const getQuestion = vi
      .fn()
      .mockResolvedValueOnce({
        data: {
          id: 4,
          questionType: 'SINGLE_CHOICE',
          status: 'DRAFT',
          stem: '单选题干',
          options: choiceOptions,
        },
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: {
          id: 4,
          questionType: 'SINGLE_CHOICE',
          status: 'ARCHIVED',
          stem: '单选题干',
          options: choiceOptions,
        },
        response: { status: 200 },
      });
    const api = renderDetail(
      { questionType: 'SINGLE_CHOICE', stem: '单选题干', options: choiceOptions },
      { getQuestion }
    );

    await user.click(await screen.findByTestId('question-archive-button'));
    await user.click(await screen.findByTestId('confirm-archive'));
    await waitFor(() => expect(api.archiveQuestion).toHaveBeenCalledWith(7, 4));

    await waitFor(() => expect(getQuestion).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId('question-restore-button')).toBeInTheDocument();
    expect(screen.queryByTestId('question-archive-button')).not.toBeInTheDocument();
    expect(screen.getByTestId('archived-notice')).toHaveTextContent('archived_at');
  });

  it('restores straight away — it is the way out of a dead end, not a risk', async () => {
    const user = userEvent.setup();
    const api = renderDetail({
      questionType: 'TRUE_FALSE',
      stem: '地球是平的',
      status: 'ARCHIVED',
    });

    await user.click(await screen.findByTestId('question-restore-button'));
    await waitFor(() => expect(api.restoreQuestion).toHaveBeenCalledWith(7, 4));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('reports a rejected archive instead of leaving the button looking dead', async () => {
    const user = userEvent.setup();
    renderDetail(
      { questionType: 'SINGLE_CHOICE', stem: '单选题干', options: choiceOptions },
      {
        archiveQuestion: vi.fn(async () => ({
          error: { message: 'gone' },
          response: { status: 500 },
        })),
      }
    );

    await user.click(await screen.findByTestId('question-archive-button'));
    await user.click(await screen.findByTestId('confirm-archive'));

    expect(await screen.findByTestId('question-mutation-error')).toHaveTextContent(
      '服务器暂时无法完成请求，请稍后重试。'
    );
  });
});
