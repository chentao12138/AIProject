/**
 * ExamsPage — create form (question picker) and list rendering.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExamsPage } from './ExamsPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

function render() {
  const createExam = vi.fn(async () => ({
    data: { id: 21, title: '期中考', status: 'DRAFT', timeLimitMinutes: 60, questions: [] },
    response: { status: 201 },
  }));
  const listQuestions = vi.fn(
    async (_spaceId: number, _query?: { status?: string }) => ({
      data: [
        { id: 29, stem: '1+1 等于几？', questionType: 'SINGLE_CHOICE', status: 'PUBLISHED' },
        { id: 30, stem: '2+2 等于几？', questionType: 'SINGLE_CHOICE', status: 'PUBLISHED' },
      ],
      response: { status: 200 },
    })
  );

  renderWithProviders(<ExamsPage />, {
    apiClient: mockApiClient({
      listExams: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      listQuestions,
      createExam,
    }),
    initialEntries: ['/spaces/7/exams'],
    routePath: '/spaces/:spaceId/exams',
  });
  return { createExam, listQuestions };
}

describe('ExamsPage', () => {
  it('renders an empty state when there are no exams', async () => {
    render();
    expect(await screen.findByText('还没有考试')).toBeInTheDocument();
  });

  it('offers only published questions', async () => {
    const { listQuestions } = render();
    expect(await screen.findByText('1+1 等于几？')).toBeInTheDocument();
    expect(listQuestions.mock.calls[0][1]).toMatchObject({ status: 'PUBLISHED' });
  });

  // The backend answers 400 "questions must not be empty". A greyed-out button
  // gave no reason, so the form now explains what is missing instead.
  it('explains why it will not submit instead of silently refusing', async () => {
    const user = userEvent.setup();
    const { createExam } = render();
    await user.type(await screen.findByLabelText('标题'), '期中考');

    const submit = screen.getByRole('button', { name: '创建考试' });
    expect(submit).toBeEnabled();
    await user.click(submit);

    expect(await screen.findByTestId('exam-form-errors')).toHaveTextContent(
      '至少选择一道题目'
    );
    expect(createExam).not.toHaveBeenCalled();
  });

  it('rejects a contract-invalid duration before the request', async () => {
    const user = userEvent.setup();
    const { createExam } = render();

    await user.type(await screen.findByLabelText('标题'), '期中考');
    await user.click(screen.getByRole('checkbox', { name: '1+1 等于几？' }));
    // `min` on a number input does not stop typing, so this would otherwise
    // reach the server and come back as a 400. (The per-question score input
    // already clamps itself to >= 1, so it needs no error path.)
    const duration = screen.getByLabelText('时长（分钟）');
    fireEvent.change(duration, { target: { value: '0' } });

    fireEvent.submit(duration.closest('form')!);

    expect(await screen.findByTestId('exam-form-errors')).toHaveTextContent(
      '时长至少为 1 分钟'
    );
    expect(createExam).not.toHaveBeenCalled();
  });

  it('submits the picked question with its score', async () => {
    const user = userEvent.setup();
    const { createExam } = render();

    const title = await screen.findByLabelText('标题');
    await user.type(title, '期中考');
    await user.type(screen.getByLabelText('说明'), '第 1-3 章');
    fireEvent.change(screen.getByLabelText('时长（分钟）'), { target: { value: '60' } });

    await user.click(await screen.findByRole('checkbox', { name: '1+1 等于几？' }));
    fireEvent.change(await screen.findByLabelText('分值'), { target: { value: '5' } });

    fireEvent.submit(title.closest('form')!);

    await waitFor(() => {
      expect(createExam).toHaveBeenCalledWith(7, {
        title: '期中考',
        description: '第 1-3 章',
        timeLimitMinutes: 60,
        questions: [{ questionId: 29, score: 5 }],
      });
    });
  });
});
