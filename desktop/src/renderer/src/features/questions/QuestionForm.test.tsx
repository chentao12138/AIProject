/**
 * QuestionForm — authoring rules (G02 / W09).
 *
 * Every assertion here is a rule the create endpoint also enforces, so the
 * form blocking a submit is the same decision the server would make — the
 * numbers come from openapi.json (`QUESTION_FIELD_LIMITS`) and the required
 * answers from `CreateQuestionRequest`'s type-specific fields. The knowledge
 * point picker is asserted against the real list endpoint because a hand-typed
 * comma list of ids was the previous behaviour and 404ed on a typo.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionForm } from './QuestionForm';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import { CONTRACT_GAP_NOTICES } from '../../lib/format';

function baseApi(overrides: Parameters<typeof mockApiClient>[0] = {}) {
  return mockApiClient({
    listKnowledgePoints: vi.fn(async () => ({
      data: [
        { id: 3, title: '牛顿第二定律' },
        { id: 5, title: '能量守恒' },
        { id: undefined, title: '没有 id 的知识点' },
      ],
      response: { status: 200 },
    })),
    createQuestion: vi.fn(async () => ({
      data: { id: 11 },
      response: { status: 200 },
    })),
    ...overrides,
  });
}

function renderForm(api = baseApi()) {
  renderWithProviders(<QuestionForm />, {
    apiClient: api,
    initialEntries: ['/spaces/7/questions/new'],
    routePath: '/spaces/:spaceId/questions/new',
  });
  return api;
}

/** Fill the two default options so only the rule under test can fail. */
async function fillChoiceBasics(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('题干'), '把下面的步骤排序');
  await user.type(screen.getByTestId('option-content-0'), '第一项');
  await user.type(screen.getByTestId('option-content-1'), '第二项');
}

async function clickCreate(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '创建' }));
}

describe('QuestionForm — required answer per type (W09)', () => {
  it('blocks a single choice with no correct option', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillChoiceBasics(user);

    await clickCreate(user);

    expect(
      await screen.findByText('单选题必须指定一个正确答案。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a multiple choice with no correct option', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await user.selectOptions(screen.getByLabelText('题型'), 'MULTIPLE_CHOICE');
    await fillChoiceBasics(user);

    await clickCreate(user);

    expect(
      await screen.findByText('多选题必须至少指定一个正确选项。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a true/false with no correct boolean', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.type(await screen.findByLabelText('题干'), '地球是平的');

    await clickCreate(user);

    expect(
      await screen.findByText('判断题必须选择正确答案（正确 / 错误）。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a short answer with no reference answer', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await user.selectOptions(screen.getByLabelText('题型'), 'SHORT_ANSWER');
    await user.type(await screen.findByLabelText('题干'), '简述原因');

    await clickCreate(user);

    expect(
      await screen.findByText('简答题必须填写参考答案。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a blank stem', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await user.type(screen.getByTestId('option-content-0'), '第一项');
    await user.type(screen.getByTestId('option-content-1'), '第二项');
    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.click(screen.getByRole('radio', { name: '正确' }));

    await clickCreate(user);

    expect(await screen.findByText('题干不能为空。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });
});

describe('QuestionForm — option keys (W09)', () => {
  it('rejects a duplicated option key instead of sending it', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillChoiceBasics(user);
    // Both rows are keyed "A".
    await user.clear(screen.getByTestId('option-key-1'));
    await user.type(screen.getByTestId('option-key-1'), 'A');
    await user.selectOptions(screen.getByLabelText('正确答案'), 'A');

    await clickCreate(user);

    expect(
      await screen.findByText('选项标识不能重复：A。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('requires a correct option that exists', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillChoiceBasics(user);
    await user.selectOptions(screen.getByLabelText('正确答案'), 'A');
    // The chosen key stops existing once the row is renamed.
    await user.clear(screen.getByTestId('option-key-0'));
    await user.type(screen.getByTestId('option-key-0'), 'X');

    await clickCreate(user);

    expect(
      await screen.findByText('单选题的正确答案必须是已存在的选项标识。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('caps option fields at the contract maxLength', () => {
    renderForm();
    expect(screen.getByTestId('option-key-0')).toHaveAttribute('maxLength', '16');
    expect(screen.getByTestId('option-content-0')).toHaveAttribute(
      'maxLength',
      '500'
    );
  });
});

describe('QuestionForm — FILL_BLANK authoring (G02)', () => {
  it('offers 填空 and sends referenceAnswer without options', async () => {
    const user = userEvent.setup();
    const api = renderForm();

    await user.selectOptions(screen.getByLabelText('题型'), 'FILL_BLANK');
    await user.type(await screen.findByLabelText('题干'), '水的化学式是 ___');
    await user.type(screen.getByLabelText('参考答案'), 'H2O');
    await user.click(screen.getByRole('checkbox', { name: '牛顿第二定律' }));
    await clickCreate(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalledTimes(1));
    const [spaceId, body] = vi.mocked(api.createQuestion).mock.calls[0];
    expect(spaceId).toBe(7);
    expect(body).toMatchObject({
      questionType: 'FILL_BLANK',
      stem: '水的化学式是 ___',
      referenceAnswer: 'H2O',
      knowledgePointIds: [3],
    });
    expect(body).not.toHaveProperty('options');
    expect(body).not.toHaveProperty('correctOptionKey');
  });

  it('caps the reference answer at the contract maxLength', async () => {
    const user = userEvent.setup();
    renderForm();
    await user.selectOptions(screen.getByLabelText('题型'), 'FILL_BLANK');

    const reference = await screen.findByLabelText('参考答案');
    expect(reference).toHaveAttribute('maxLength', '4000');
    expect(screen.getByLabelText('解析')).toHaveAttribute('maxLength', '4000');
  });
});

describe('QuestionForm — types the contract cannot author (G02)', () => {
  it('lists 排序 and 配对 but refuses to select them, naming the gap', () => {
    renderForm();

    const ordering = screen.getByRole('option', { name: '排序（暂不可创建）' });
    const matching = screen.getByRole('option', { name: '配对（暂不可创建）' });
    expect(ordering).toBeDisabled();
    expect(matching).toBeDisabled();

    const notes = screen.getByTestId('authoring-gap-notes');
    expect(notes).toHaveTextContent(CONTRACT_GAP_NOTICES.ORDERING_AUTHORING);
    expect(notes).toHaveTextContent(CONTRACT_GAP_NOTICES.MATCHING);
    // A disabled option cannot become the form's type.
    expect(screen.getByLabelText('题型')).toHaveValue('SINGLE_CHOICE');
  });
});

describe('QuestionForm — knowledge point picker (W09)', () => {
  it('replaces the comma-id input with the real point list', async () => {
    const user = userEvent.setup();
    const api = renderForm();

    expect(
      await screen.findByRole('checkbox', { name: '能量守恒' })
    ).toBeInTheDocument();
    expect(screen.queryByLabelText(/逗号分隔/)).not.toBeInTheDocument();

    await user.type(await screen.findByLabelText('题干'), '关于力');
    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.click(screen.getByRole('radio', { name: '错误' }));
    await user.click(screen.getByRole('checkbox', { name: '牛顿第二定律' }));
    await user.click(screen.getByRole('checkbox', { name: '能量守恒' }));
    await clickCreate(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalledTimes(1));
    const [, body] = vi.mocked(api.createQuestion).mock.calls[0];
    expect(body.knowledgePointIds).toEqual([3, 5]);
  });

  it('never offers a point whose id is not a positive integer', async () => {
    renderForm();
    await screen.findByRole('checkbox', { name: '牛顿第二定律' });
    expect(
      screen.queryByRole('checkbox', { name: '没有 id 的知识点' })
    ).not.toBeInTheDocument();
  });

  it('omits knowledgePointIds when nothing is selected', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.type(await screen.findByLabelText('题干'), '水在 0 度结冰');
    await user.click(screen.getByRole('radio', { name: '正确' }));
    await clickCreate(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalledTimes(1));
    const [, body] = vi.mocked(api.createQuestion).mock.calls[0];
    expect(body).not.toHaveProperty('knowledgePointIds');
  });
});

describe('QuestionForm — duplicate submit protection (W09)', () => {
  it('disables the fields and posts exactly once while the create is pending', async () => {
    const user = userEvent.setup();
    let settle: (value: unknown) => void = () => {};
    const api = renderForm(
      baseApi({
        createQuestion: vi.fn(
          () =>
            new Promise((resolve) => {
              settle = resolve;
            })
        ),
      })
    );

    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.type(await screen.findByLabelText('题干'), '地球绕太阳转');
    await user.click(screen.getByRole('radio', { name: '正确' }));

    const submit = screen.getByRole('button', { name: '创建' });
    await user.click(submit);
    await waitFor(() => expect(api.createQuestion).toHaveBeenCalledTimes(1));

    expect(submit).toBeDisabled();
    expect(submit).toHaveTextContent('创建中…');
    expect(screen.getByLabelText('题干')).toBeDisabled();
    expect(screen.getByLabelText('题型')).toBeDisabled();

    // A second click (or Enter on the form) must not POST again.
    await user.click(submit);
    await user.click(submit);
    expect(api.createQuestion).toHaveBeenCalledTimes(1);

    // The guard belongs to `pending`, not to the click: once the request
    // settles the form is usable again (a 500 keeps the form mounted).
    settle({ error: { message: 'boom' }, response: { status: 500 } });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '创建' })).toBeEnabled()
    );
    expect(
      await screen.findByText('服务器暂时无法完成请求，请稍后重试。')
    ).toBeInTheDocument();
    expect(api.createQuestion).toHaveBeenCalledTimes(1);
  });
});

describe('QuestionForm — server errors', () => {
  it('shows the normalized rejection and keeps the form usable', async () => {
    const user = userEvent.setup();
    const api = renderForm(
      baseApi({
        createQuestion: vi.fn(async () => ({
          error: { code: 'BAD_REQUEST', detail: 'option keys must be unique' },
          response: { status: 400 },
        })),
      })
    );

    await user.selectOptions(screen.getByLabelText('题型'), 'TRUE_FALSE');
    await user.type(await screen.findByLabelText('题干'), '月亮自己发光');
    await user.click(screen.getByRole('radio', { name: '错误' }));
    await clickCreate(user);

    expect(
      await screen.findByText('校验未通过：option keys must be unique')
    ).toBeInTheDocument();
    expect(screen.getByLabelText('题干')).toHaveValue('月亮自己发光');
    expect(api.createQuestion).toHaveBeenCalledTimes(1);
  });
});
