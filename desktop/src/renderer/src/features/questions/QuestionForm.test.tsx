/**
 * QuestionForm (W09 / G02 / G09) — the create-only authoring form.
 *
 * The tests below are the rules the endpoint enforces too, so a green form is
 * a request the backend accepts: unique option keys, one correct answer per
 * type, contract-length caps, a real knowledge-point picker instead of a
 * hand-typed comma list of ids, and one POST per click while that POST is in
 * flight (there is no DELETE to undo a duplicate).
 *
 * ORDERING and MATCHING are asserted on the WIRE, not on the DOM:
 * `correctOrder` must be the 1-based permutation the server grades with
 * (AnswerPayloadView.orderingAnswer items carry `minimum: 1`), and `matches`
 * must be a JSON string of `left`/`right` objects — `gradeMatching` compares
 * whole maps, so one extra key silently makes every answer wrong.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionForm } from './QuestionForm';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import { CONTRACT_GAP_NOTICES, QUESTION_FIELD_LIMITS } from '../../lib/format';

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

  it('offers all seven types, because the contract now carries all seven answers', () => {
    renderForm();
    // ORDERING / MATCHING became authorable when CreateQuestionRequest grew
    // correctOrder / matches — nothing is disabled any more, so a disabled
    // option here would mean the form is hiding a field the server accepts.
    expect(typeOptions()).toEqual([
      { value: 'SINGLE_CHOICE', disabled: false },
      { value: 'MULTIPLE_CHOICE', disabled: false },
      { value: 'TRUE_FALSE', disabled: false },
      { value: 'SHORT_ANSWER', disabled: false },
      { value: 'FILL_BLANK', disabled: false },
      { value: 'ORDERING', disabled: false },
      { value: 'MATCHING', disabled: false },
    ]);
    expect(screen.getByLabelText('题型')).toHaveValue('SINGLE_CHOICE');
    expect(screen.queryByTestId('authoring-gap-notes')).not.toBeInTheDocument();
  });

  it('reveals the ordering editor when 排序 is picked and the pair editor for 配对', async () => {
    const user = userEvent.setup();
    renderForm();

    await pickType(user, 'ORDERING');
    expect(await screen.findByTestId('ordering-editor')).toBeInTheDocument();
    expect(screen.queryByTestId('matching-editor')).not.toBeInTheDocument();
    expect(screen.getByText('排序')).toBeInTheDocument();

    await pickType(user, 'MATCHING');
    expect(await screen.findByTestId('matching-editor')).toBeInTheDocument();
    expect(screen.queryByTestId('ordering-editor')).not.toBeInTheDocument();
  });

  it('states that the order and the pairs cannot be read back, per type', async () => {
    const user = userEvent.setup();
    renderForm();

    // The read side (QuestionAnswerView) has no correctOrder / matchesJson, so
    // the notice has to name that field rather than imply the author can review
    // what they set.
    await pickType(user, 'ORDERING');
    expect(await screen.findByTestId('ordering-oneway-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.ORDERING_REVIEW
    );

    await pickType(user, 'MATCHING');
    expect(await screen.findByTestId('matching-oneway-notice')).toHaveTextContent(
      CONTRACT_GAP_NOTICES.MATCHING_REVIEW
    );
  });
});

describe('QuestionForm — ORDERING authoring (G02)', () => {
  async function fillThreeItems(user: ReturnType<typeof userEvent.setup>) {
    await fillStem(user, '把下列步骤按顺序排列。');
    await fillOptions(user, ['第一步', '第二步']);
    await user.click(screen.getByRole('button', { name: '添加条目' }));
    await user.type(screen.getByTestId('option-content-2'), '第三步');
  }

  it('sends correctOrder as 1-based option positions', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'ORDERING');
    await fillThreeItems(user);

    // The items are entered 第一/第二/第三步; the standard order is 第三步 first,
    // then 第一步, then 第二步 — which on the wire is the server's own example
    // (AnswerDataCodec.java:23 `{"correctOrder":[3,1,2]}` for three items).
    await user.click(screen.getByRole('button', { name: '下移标准顺序第 2 位' }));
    expect(await screen.findByTestId('correct-order-preview')).toHaveTextContent(
      'correctOrder = [1, 3, 2]'
    );
    await user.click(screen.getByRole('button', { name: '上移标准顺序第 2 位' }));
    expect(screen.getByTestId('correct-order-preview')).toHaveTextContent(
      'correctOrder = [3, 1, 2]'
    );

    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    expect(sentBody(api)).toEqual({
      questionType: 'ORDERING',
      stem: '把下列步骤按顺序排列。',
      options: [
        { optionKey: 'A', content: '第一步' },
        { optionKey: 'B', content: '第二步' },
        { optionKey: 'C', content: '第三步' },
      ],
      correctOrder: [3, 1, 2],
    });
  });

  it('never puts a 0 in correctOrder, even before the order is touched', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'ORDERING');
    await fillStem(user, '默认顺序即可。');
    await fillOptions(user, ['甲', '乙']);

    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    const order = sentBody(api).correctOrder ?? [];
    expect(order).toEqual([1, 2]);
    expect(order).not.toContain(0);
  });

  it('blocks an order that leaves an item out, and says which one', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'ORDERING');
    await fillThreeItems(user);

    await user.click(screen.getByRole('button', { name: '将标准顺序第 3 位移出' }));
    expect(
      await screen.findByTestId('ordering-unplaced')
    ).toBeInTheDocument();

    await submit(user);

    expect(
      await screen.findByText('标准顺序里还缺少 1 个条目：第三步。')
    ).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();

    // Placing it back un-blocks the submit without reloading the form.
    await user.click(screen.getByTestId('ordering-place-2'));
    await submit(user);
    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    expect(sentBody(api).correctOrder).toEqual([1, 2, 3]);
  });

  it('blocks fewer than two items and a duplicated option key', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'ORDERING');
    await fillStem(user, '排序题');
    await fillOptions(user, ['甲', '乙']);
    await user.clear(screen.getByTestId('option-key-1'));
    await user.type(screen.getByTestId('option-key-1'), 'A');

    await submit(user);

    expect(await screen.findByText('条目标识不能重复：A。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('caps the item inputs at the option contract limits', async () => {
    const user = userEvent.setup();
    renderForm();
    await pickType(user, 'ORDERING');

    expect(await screen.findByTestId('option-key-0')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.optionKey)
    );
    expect(screen.getByTestId('option-content-0')).toHaveAttribute(
      'maxLength',
      String(QUESTION_FIELD_LIMITS.optionContent)
    );
  });
});

describe('QuestionForm — MATCHING authoring (G02)', () => {
  async function fillColumns(user: ReturnType<typeof userEvent.setup>) {
    await fillStem(user, '把城市与所属国家配对。');
    await user.type(screen.getByTestId('match-left-0'), ' 北京 ');
    await user.type(screen.getByTestId('match-right-0'), ' 中国 ');
    await user.type(screen.getByTestId('match-left-1'), '巴黎');
    await user.type(screen.getByTestId('match-right-1'), '法国');
  }

  function pairRow(leftIndex: number) {
    return screen.getByTestId(`match-pair-${leftIndex}`);
  }

  async function pairAll(user: ReturnType<typeof userEvent.setup>) {
    await user.selectOptions(pairRow(0), '0');
    await user.selectOptions(pairRow(1), '1');
  }

  it('sends the columns as options and the standard pairs as key references', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'MATCHING');
    await fillColumns(user);
    await pairAll(user);

    await submit(user);

    await waitFor(() => expect(api.createQuestion).toHaveBeenCalled());
    const body = sentBody(api);
    expect(body.questionType).toBe('MATCHING');
    expect(body.stem).toBe('把城市与所属国家配对。');
    // The learner's widget rebuilds the two columns by cutting the flat option
    // list in half (`splitMatchingOptions`), so the columns must be stored
    // left-first — and `matches` has to name the option keys, because that is
    // what the answer payload sends. Pairs of text would grade nothing.
    expect(body.options).toEqual([
      { optionKey: 'L1', content: ' 北京 ' },
      { optionKey: 'L2', content: '巴黎' },
      { optionKey: 'R1', content: ' 中国 ' },
      { optionKey: 'R2', content: '法国' },
    ]);
    expect(typeof body.matches).toBe('string');
    const parsed = JSON.parse(String(body.matches)) as Array<Record<string, string>>;
    expect(parsed).toEqual([
      { left: 'L1', right: 'R1' },
      { left: 'L2', right: 'R2' },
    ]);
    for (const pair of parsed) {
      // `gradeMatching` compares whole maps, so a stray or renamed key is a
      // pair that can never match.
      expect(Object.keys(pair).sort()).toEqual(['left', 'right']);
    }
  });

  it('blocks a blank column entry and an unpaired left item before the request goes out', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'MATCHING');
    await fillStem(user, '把城市与所属国家配对。');
    await user.type(screen.getByTestId('match-left-0'), '北京');

    await submit(user);

    expect(await screen.findByText('第 1 个右侧条目缺少内容。')).toBeInTheDocument();
    expect(screen.getByText('「北京」还没有标准配对，请为它选择一个右侧条目。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('blocks when a column has nothing left in it', async () => {
    const user = userEvent.setup();
    const api = renderForm();
    await pickType(user, 'MATCHING');
    await fillStem(user, '配对题');

    await user.click(screen.getByRole('button', { name: '删除左侧条目 1' }));
    await user.click(screen.getByRole('button', { name: '删除左侧条目 1' }));

    await submit(user);

    expect(await screen.findByText('配对题至少需要 1 个左侧条目。')).toBeInTheDocument();
    expect(api.createQuestion).not.toHaveBeenCalled();
  });

  it('shows the real serialized payload against the contract cap', async () => {
    const user = userEvent.setup();
    renderForm();
    await pickType(user, 'MATCHING');

    // Two unpaired rows serialize to `[]`: the counter reports the payload the
    // server would receive, not the characters typed.
    const wire = await screen.findByTestId('matches-wire');
    expect(wire).toHaveTextContent('matches = []');
    expect(wire).toHaveTextContent(`${QUESTION_FIELD_LIMITS.matches} 字符`);

    await fillColumns(user);
    await pairAll(user);
    expect(screen.getByTestId('matches-wire')).toHaveTextContent('"left":"L1","right":"R1"');
  });

  it('previews the pairs as readable text, not as JSON to type', async () => {
    const user = userEvent.setup();
    renderForm();
    await pickType(user, 'MATCHING');
    await fillColumns(user);

    // Before pairing anything: the author is owed the unpaired state, not a
    // silently optimistic preview.
    expect(
      screen.getAllByTestId('matches-preview-item').map((node) => node.textContent)
    ).toEqual(['北京 → （未配对）', '巴黎 → （未配对）']);

    await pairAll(user);

    const preview = await screen.findAllByTestId('matches-preview-item');
    expect(preview.map((node) => node.textContent)).toEqual([
      '北京 → 中国',
      '巴黎 → 法国',
    ]);
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

  it('states the grading rule the server actually applies', async () => {
    const user = userEvent.setup();
    renderForm();
    await pickType(user, 'FILL_BLANK');

    // QuestionAnswerEvaluator.gradeFillBlank compares the whole trimmed string,
    // so a hint promising per-blank matching would teach authors a rule that
    // silently marks correct learners wrong.
    expect(await screen.findByText(/整串精确比对/)).toBeInTheDocument();
    expect(screen.queryByText(/逐项/)).not.toBeInTheDocument();
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
