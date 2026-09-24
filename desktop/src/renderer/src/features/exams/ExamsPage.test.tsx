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

  // The backend answers 400 "questions must not be empty", so a form that had
  // no way to pick a question could never create an exam at all.
  it('refuses to submit until at least one question is picked', async () => {
    const { createExam } = render();
    await userEvent.type(await screen.findByLabelText('标题'), '期中考');
    expect(screen.getByRole('button', { name: '创建考试' })).toBeDisabled();
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
        durationMinutes: 60,
        questions: [{ questionId: 29, score: 5 }],
      });
    });
  });
});
