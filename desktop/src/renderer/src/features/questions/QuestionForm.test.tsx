/**
 * QuestionForm (W09 / G02) — the create-only authoring form.
 *
 * The tests below are the rules the endpoint enforces too, so a green form is
 * a request the backend accepts: unique option keys, one correct answer per
 * type, contract-length caps, a real knowledge-point picker instead of a
 * hand-typed comma list of ids, and one POST per click while that POST is in
 * flight (there is no DELETE to undo a duplicate).
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionForm } from './QuestionForm';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import { QUESTION_FIELD_LIMITS } from '../../lib/format';

const ROUTE = '/spaces/:spaceId/questions/new';

function knowledgePoints() {
  return mockApiClient({
    listKnowledgePoints: vi.fn(async () => ({
      data: [
        { id: 3, title: '牛顿第二定律' },
        { id: 5, title: '能量守恒' },
        { id: undefined, title: '缺 id 的知识点' },
      ],
      response: { status: 200 },
    })),
    createQuestion: vi.fn(async () => ({
      data: { id: 41 },
      response: { status: 200 },
    })),
  });
}

function renderForm(api = knowledgePoints()) {
  renderWithProviders(<QuestionForm />, {
    apiClient: api,
    initialEntries: [ROUTE.replace(':spaceId', '7').replace(':questionId', '1')],
    routePath: ROUTE,
  });
  return api;
}

/** The single POST the mocks received. */
function sentBody(api: ReturnType<typeof knowledgePoints>) {
  const calls = vi.mocked(api.createQuestion).mock.calls;
  expect(calls).toHaveLength(1);
  return calls[0][1];
}

async function fillStem(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.type(await screen.findByLabelText('题干'), text);
}

async function pickType(
  user: ReturnType<typeof userEvent.setup>,
  type: string
) {
  await user.selectOptions(screen.getByLabelText('题型'), type);
}

async function fillOptions(
  user: ReturnType<typeof userEvent.setup>,
  contents: string[]
) {
  for (const [index, content] of contents.entries()) {
    await user.type(screen.getByTestId(`option-content-${index}`), content);
  }
}

async function submit(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '创建' }));
}

describe('QuestionForm — type list follows the contract (G02)', () => {
  /** The select's own option table: value + disabled flag, in render order. */
  function typeOptions(): Array<{ value: string; disabled: boolean }> {
    const select = screen.getByLabelText<HTMLSelectElement>('题型');
    return Array.from(select.options).map((option) => ({
      value: option.value,
      disabled: option.disabled,
    }));
  }

  it('offers the five types whose answer the contract can carry', () => {
    renderForm();
    expect(typeOptions()).toEqual([
      { value: 'SINGLE_CHOICE', disabled: false },
      { value: 'MULTIPLE_CHOICE', disabled: false },
      { value: 'TRUE_FALSE', disabled: false },
      { value: 'SHORT_ANSWER', disabled: false },
      { value: 'FILL_BLANK', disabled: false },
      { value: 'ORDERING', disabled: true },
      { value: 'MATCHING', disabled: true },
    ]);
    expect(screen.getByLabelText('题型')).toHaveValue('SINGLE_CHOICE');
  });

  it('shows 排序 and 配对 but keeps them unselectable, naming the missing fields', () => {
    renderForm();
    expect(screen.getByRole('option', { name: '排序（暂不可创建）' })).toBeDisabled();
    expect(screen.getByRole('option', { name: '配对（暂不可创建）' })).toBeDisabled();

    const notes = screen.getByTestId('authoring-gap-notes');
    expect(notes).toHaveTextContent('CreateQuestionRequest');
    expect(notes).toHaveTextContent('C09');
    expect(notes).toHaveTextContent('pairs');
    expect(screen.getByLabelText('题型')).toHaveValue('SINGLE_CHOICE');
  });
});

describe('QuestionForm — required answer per type (W09)', () => {
  it('blocks a single choice with no correct option', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillStem(user, '中国的首都是？');
    await fillOptions(user, ['北京', '上海']);

    await submit(user);

    expect(await screen.findByText('单选题必须指定一个正确答案。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a multiple choice with no correct option', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'MULTIPLE_CHOICE');
    await fillStem(user, '哪些是直辖市？');
    await fillOptions(user, ['北京', '上海']);

    await submit(user);

    expect(
      await screen.findByText('多选题必须至少指定一个正确选项。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a true/false with no correct boolean', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '地球是平的');

    await submit(user);

    expect(
      await screen.findByText('判断题必须选择正确答案（正确 / 错误）。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a short answer with no reference answer', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'SHORT_ANSWER');
    await fillStem(user, '简述四季成因');

    await submit(user);

    expect(await screen.findByText('简答题必须填写参考答案。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks a blank stem even when everything else is filled', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'TRUE_FALSE');
    await user.click(screen.getByRole('radio', { name: '正确' }));

    await submit(user);

    expect(await screen.findByText('题干不能为空。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });
});

describe('QuestionForm — option keys (W09)', () => {
  it('rejects a duplicated option key instead of sending it', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillStem(user, '中国的首都是？');
    await fillOptions(user, ['北京', '上海']);
    await user.clear(screen.getByTestId('option-key-1'));
    await user.type(screen.getByTestId('option-key-1'), 'A');

    await submit(user);

    expect(await screen.findByText('选项标识不能重复：A。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('requires the chosen correct option to still exist', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await fillStem(user, '中国的首都是？');
    await fillOptions(user, ['北京', '上海']);
    await user.selectOptions(screen.getByLabelText('正确答案'), 'A');
    await user.clear(screen.getByTestId('option-key-0'));
    await user.type(screen.getByTestId('option-key-0'), 'X');

    await submit(user);

    expect(
      await screen.findByText('单选题的正确答案必须是已存在的选项标识。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('caps option inputs at the contract maxLength', () => {
    renderForm();
    expect(screen.getByTestId('option-key-0')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.optionKey)
    );
    expect(screen.getByTestId('option-content-0')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.optionContent)
    );
  });
});

describe('QuestionForm — FILL_BLANK closes the authoring loop (G02)', () => {
  it('sends referenceAnswer and no options', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'FILL_BLANK');
    await fillStem(user, '水的化学式是 ___');
    await user.type(screen.getByLabelText('参考答案'), 'H2O');

    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    expect(sentBody(api)).toEqual({
      questionType: 'FILL_BLANK',
      stem: '水的化学式是 ___',
      referenceAnswer: 'H2O',
    });
  });

  it('caps the reference answer at the contract maxLength', async () => {
    const user = userEvent.setup();
    renderForm();
    await pickType(user, 'FILL_BLANK');
    expect(await screen.findByLabelText('参考答案')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.referenceAnswer)
    );
    expect(screen.getByLabelText('解析')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.explanation)
    );
  });
});

describe('QuestionForm — knowledge point picker (W09)', () => {
  it('replaces the comma-id input with the space’s real points', async () => {
    const user = userEvent.setup();
    const api = renderForm();

    await screen.findByRole('checkbox', { name: '能量守恒' });
    expect(screen.queryByLabelText(/逗号分隔/)).not.toBeInTheDocument();

    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '能量守恒定律成立');
    await user.click(screen.getByRole('radio', { name: '正确' }));
    await user.click(screen.getByRole('checkbox', { name: '牛顿第二定律' }));
    await user.click(screen.getByRole('checkbox', { name: '能量守恒' }));
    expect(screen.getByText(/已选 2 个知识点：#3、#5/)).toBeInTheDocument();

    // A second click on a checked point un-picks it, exactly like the box.
    await user.click(screen.getByRole('checkbox', { name: '能量守恒' }));
    expect(screen.getByText(/已选 1 个知识点：#3/)).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: '能量守恒' }));
    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    expect(sentBody(api)).toMatchObject({ knowledgePointIds: [3, 5] });
  });

  it('never offers a point without a positive id', async () => {
    renderForm();
    await screen.findByRole('checkbox', { name: '能量守恒' });
    expect(
      screen.queryByRole('checkbox', { name: '缺 id 的知识点' })
    ).not.toBeInTheDocument();
  });

  it('omits knowledgePointIds when nothing is picked', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '水在标准大气压下 100 度沸腾');
    await user.click(screen.getByRole('radio', { name: '正确' }));

    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    expect(sentBody(api)).not.toHaveProperty('knowledgePointIds');
  });

  it('reports a failed point list without blocking the form', async () => {
    const user = userEvent.setup();
    const api = mockApiClient({
      listKnowledgePoints: vi.fn(async () => ({
        error: { message: 'boom' },
        response: { status: 500 },
      })),
      createQuestion: vi.fn(async () => ({ data: { id: 1 }, response: { status: 200 } })),
    });
    renderWithProviders(<QuestionForm />, {
      apiClient: api,
      initialEntries: ['/spaces/7/questions/new'],
      routePath: ROUTE,
    });

    expect(await screen.findByText(/知识点列表加载失败/)).toBeInTheDocument();
    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '月亮反射太阳光');
    await user.click(screen.getByRole('radio', { name: '正确' }));
    await submit(user);
    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
  });
});

describe('QuestionForm — one POST at a time (W09)', () => {
  it('disables the fields and posts once while the create is pending', async () => {
    const user = userEvent.setup();
    let settle: (value: unknown) => void = () => {};
    const api = mockApiClient({
      listKnowledgePoints: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createQuestion: vi.fn(
        () =>
          new Promise((resolve) => {
            settle = resolve;
          })
      ),
    });
    renderWithProviders(<QuestionForm />, {
      apiClient: api,
      initialEntries: ['/spaces/7/questions/new'],
      routePath: ROUTE,
    });

    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '地球绕太阳转');
    await user.click(screen.getByRole('radio', { name: '正确' }));

    const submitButton = screen.getByRole('button', { name: '创建' });
    await user.click(submitButton);
    await waitFor(() => expect(api.createQuestion).toHaveBeenCalledTimes(1));

    expect(submitButton).toBeDisabled();
    expect(submitButton).toHaveTextContent('创建中…');
    expect(screen.getByLabelText('题干')).toBeDisabled();
    expect(screen.getByLabelText('题型')).toBeDisabled();

    await user.click(submitButton);
    await user.click(submitButton);
    expect(api.createQuestion).toHaveBeenCalledTimes(1);

    // The guard belongs to `pending`: a rejected create re-arms the form,
    // because there is no edit endpoint to fix a half-saved question.
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

describe('QuestionForm — server validation copy', () => {
  it('surfaces the backend’s validation detail', async () => {
    const user = userEvent.setup();
    const api = mockApiClient({
      listKnowledgePoints: vi.fn(async () => ({ data: [], response: { status: 200 } })),
      createQuestion: vi.fn(async () => ({
        error: { code: 'BAD_REQUEST', detail: 'option keys must be unique' },
        response: { status: 400 },
      })),
    });
    renderWithProviders(<QuestionForm />, {
      apiClient: api,
      initialEntries: ['/spaces/7/questions/new'],
      routePath: ROUTE,
    });

    await pickType(user, 'TRUE_FALSE');
    await fillStem(user, '月亮反射太阳光');
    await user.click(screen.getByRole('radio', { name: '正确' }));
    await submit(user);

    expect(
      await screen.findByText('校验未通过：option keys must be unique')
    ).toBeInTheDocument();
    expect(screen.getByLabelText('题干')).toHaveValue('月亮反射太阳光');
  });
});
