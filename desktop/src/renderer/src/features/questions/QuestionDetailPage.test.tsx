/**
 * QuestionDetailPage — review leg of G02 plus the honest gap copy.
 *
 * The detail page is where a question is read back, so it is where "did the
 * author actually record a correct answer?" becomes visible:
 *
 *  - FILL_BLANK shows its reference answer (the loop closes).
 *  - ORDERING / MATCHING keep their draft but lose the 发布 button: publishing
 *    an item whose correct answer the contract cannot carry would hand
 *    practice/exam an ungradeable question. The notice names the missing
 *    fields instead of staying silent.
 *  - Edit / delete are absent by contract, and say so (G04 / G09).
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

describe('QuestionDetailPage — types the contract cannot grade', () => {
  it('keeps an ORDERING draft unpublishable and names the missing field', async () => {
    renderDetail({
      questionType: 'ORDERING',
      stem: '把下面的步骤排序。',
      options: choiceOptions,
      answer: {},
    });

    expect(await screen.findByTestId('publish-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.ORDERING_AUTHORING
    );
    expect(screen.queryByRole('button', { name: '发布' })).not.toBeInTheDocument();
    // The renderer flags the same gap on the review side.
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent('C09');
  });

  it('renders MATCHING read-only with no answer widget', async () => {
    renderDetail({
      questionType: 'MATCHING',
      stem: '把概念与定义配对。',
      options: choiceOptions,
      answer: {},
    });

    expect(await screen.findByTestId('matching-readonly')).toBeInTheDocument();
    expect(screen.getByTestId('publish-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.MATCHING
    );
    expect(screen.queryByRole('button', { name: '发布' })).not.toBeInTheDocument();
  });

  it('leaves a published ungradeable draft alone without a publish button', async () => {
    renderDetail(
      {
        questionType: 'ORDERING',
        stem: '把下面的步骤排序。',
        options: choiceOptions,
        answer: {},
      },
      {
        getQuestion: vi.fn(async () => ({
          data: { id: 4, questionType: 'ORDERING', status: 'PUBLISHED', stem: 'x', options: choiceOptions },
          response: { status: 200 },
        })),
      }
    );

    await screen.findByTestId('contract-gap-notice');
    expect(screen.queryByRole('button', { name: '发布' })).not.toBeInTheDocument();
    // The notice belongs to a draft that could still be fixed, not to a
    // published question.
    expect(screen.queryByTestId('publish-gap-notice')).not.toBeInTheDocument();
  });
});

describe('QuestionDetailPage — no update / delete verb exists (G04 / G09)', () => {
  it('states the contract gap where an edit or delete button would be', async () => {
    renderDetail({
      questionType: 'SINGLE_CHOICE',
      stem: '单选题干',
      options: choiceOptions,
      answer: { correctOptionKey: 'A' },
    });

    expect(await screen.findByTestId('update-gap-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.QUESTION_UPDATE
    );
    expect(
      screen.queryByRole('button', { name: /编辑|删除|归档/ })
    ).not.toBeInTheDocument();
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
