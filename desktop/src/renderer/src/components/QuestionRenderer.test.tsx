/**
 * QuestionRenderer — the three types G02 added (FILL_BLANK / ORDERING /
 * MATCHING), plus the behavior the exams and practice pages already depend on.
 *
 * ORDERING and MATCHING are asserted by the WIRE VALUE they emit, because that
 * is what the backend grades: `orderingAnswer` is an array of 1-based option
 * positions (`QuestionAnswerEvaluator.gradeOrdering` compares it with the
 * stored order for exact list equality and 400s on an empty one), and
 * `matchingAnswer` is a JSON string of `{left,right}` maps compared as a SET by
 * `gradeMatching`. An incomplete sequence or an unpaired left item therefore has
 * to be impossible to submit, not merely discouraged.
 *
 * The component is fully controlled: it emits the new value and the page owns
 * the state, which is what lets the practice card label one question's save
 * state without a shared flag.
 */

import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionRenderer, type QuestionPayload } from './QuestionRenderer';
import { parseMatchingPairs, splitMatchingOptions } from './answer-grammar';

/**
 * A page holding the answer state, because the renderer is fully controlled:
 * typing into an unheld textarea would just re-emit one character per
 * keystroke, which is a property of the component worth pinning.
 */
function AnsweringPage({
  question,
  onSubmit = () => {},
  submitLabel,
  onPayload = () => {},
}: {
  question: QuestionPayload;
  onSubmit?: () => void;
  submitLabel?: string;
  /** What the page would send, so a test can read the payload back. */
  onPayload?: (payload: { orderingAnswer?: number[]; matchingAnswer?: string }) => void;
}) {
  const [selectedOptionKeys, setSelectedOptionKeys] = useState<string[]>([]);
  const [textAnswer, setTextAnswer] = useState('');
  const [orderingAnswer, setOrderingAnswer] = useState<number[] | undefined>();
  const [matchingAnswer, setMatchingAnswer] = useState<string | undefined>();
  return (
    <QuestionRenderer
      question={question}
      mode="answer"
      selectedOptionKeys={selectedOptionKeys}
      textAnswer={textAnswer}
      orderingAnswer={orderingAnswer}
      matchingAnswer={matchingAnswer}
      onSelectedOptionKeysChange={setSelectedOptionKeys}
      onTextAnswerChange={setTextAnswer}
      onOrderingAnswerChange={(value) => {
        setOrderingAnswer(value);
        onPayload({ orderingAnswer: value });
      }}
      onMatchingAnswerChange={(value) => {
        setMatchingAnswer(value);
        onPayload({ matchingAnswer: value });
      }}
      onSubmit={() =>
        onSubmit()
      }
      submitLabel={submitLabel}
    />
  );
}

const choiceOptions = [
  { optionKey: 'A', content: '第一项' },
  { optionKey: 'B', content: '第二项' },
  { optionKey: 'C', content: '第三项' },
];

const matchingOptions = [
  { optionKey: 'A', content: '巴黎' },
  { optionKey: 'B', content: '东京' },
  { optionKey: '1', content: '法国' },
  { optionKey: '2', content: '日本' },
];

function question(questionType: string): QuestionPayload {
  return { questionType, stem: '题干内容', options: choiceOptions };
}

function matchingQuestion(): QuestionPayload {
  return { questionType: 'MATCHING', stem: '把城市与国家配对', options: matchingOptions };
}

/** The order the component is showing, read off the rows. */
function orderingKeys(): (string | null)[] {
  return screen
    .getAllByTestId('ordering-item')
    .map((el) => el.getAttribute('data-option-key'));
}

function orderingTexts(): (string | undefined)[] {
  return screen.getAllByTestId('ordering-item').map(
    (el) => el.querySelector('.question-renderer__ordering-text')?.textContent
  );
}

describe('QuestionRenderer — FILL_BLANK (G02)', () => {
  it('collects a text answer and gates submit on it, like SHORT_ANSWER', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(
      <AnsweringPage
        question={{ questionType: 'FILL_BLANK', stem: '水的化学式是 ___。' }}
        onSubmit={onSubmit}
      />
    );

    const box = screen.getByRole('textbox', { name: '作答' });
    const submit = screen.getByRole('button', { name: '提交' });
    // No answer yet -> submit disabled, instead of POSTing an empty string.
    expect(submit).toBeDisabled();

    await user.type(box, 'H2O');
    expect(box).toHaveValue('H2O');
    expect(submit).toBeEnabled();
    await user.click(submit);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('holds no answer text of its own', async () => {
    const user = userEvent.setup();
    const onTextAnswerChange = vi.fn();
    render(
      <QuestionRenderer
        question={{ questionType: 'FILL_BLANK', stem: '水的化学式是 ___。' }}
        mode="answer"
        textAnswer=""
        onTextAnswerChange={onTextAnswerChange}
      />
    );

    await user.type(screen.getByRole('textbox', { name: '作答' }), 'ab');
    expect(onTextAnswerChange).toHaveBeenNthCalledWith(1, 'a');
    expect(onTextAnswerChange).toHaveBeenNthCalledWith(2, 'b');
  });

  it('shows no text box in answer mode when the page cannot receive it', () => {
    // A textarea whose value is thrown away is worse than none: the practice
    // card kept its own FILL_BLANK control while this component had no branch,
    // and wiring a second dead box would have shown two of them.
    render(
      <QuestionRenderer
        question={{ questionType: 'FILL_BLANK', stem: '水的化学式是 ___。' }}
        mode="answer"
      />
    );
    expect(screen.queryByRole('textbox', { name: '作答' })).not.toBeInTheDocument();
    // Review still shows the stored reference answer.
    render(
      <QuestionRenderer
        question={{ questionType: 'FILL_BLANK', stem: '水的化学式是 ___。' }}
        answer={{ referenceAnswer: 'H2O' }}
        mode="review"
      />
    );
    expect(screen.getByRole('textbox', { name: '作答' })).toBeDisabled();
  });

  it('shows the reference answer in review and never claims a gap for it', () => {
    render(
      <QuestionRenderer
        question={{ questionType: 'FILL_BLANK', stem: '水的化学式是 ___。' }}
        answer={{ referenceAnswer: 'H2O', isCorrect: true }}
        mode="review"
        textAnswer="H2O"
      />
    );

    expect(screen.getByTestId('reference-answer')).toHaveTextContent('H2O');
    expect(screen.queryByTestId('contract-gap-notice')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '作答' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '提交' })).not.toBeInTheDocument();
  });
});

describe('QuestionRenderer — keyboard submit without a second button', () => {
  it('lets a host that owns the submit control still receive Enter', async () => {
    const user = userEvent.setup();
    const onTextEnterSubmit = vi.fn();

    render(
      <QuestionRenderer
        question={{ questionType: 'SHORT_ANSWER', stem: '简答题干' }}
        mode="answer"
        onTextAnswerChange={() => {}}
        onTextEnterSubmit={onTextEnterSubmit}
      />
    );

    // `onSubmit` would draw a button; the practice card already renders its own
    // per-question submit with saving/error labels, so this path must not add a
    // second one while still keeping Enter-to-send.
    expect(
      screen.queryByRole('button', { name: '提交' })
    ).not.toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: '作答' }), '{Enter}');
    expect(onTextEnterSubmit).toHaveBeenCalledTimes(1);
  });
});

describe('QuestionRenderer — ORDERING (G02 / C09)', () => {
  it('renders a keyboard-operable sequence and refuses to submit an uncommitted one', () => {
    const onOrderingAnswerChange = vi.fn();
    const onSubmit = vi.fn();
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        onOrderingAnswerChange={onOrderingAnswerChange}
        onSubmit={onSubmit}
      />
    );

    const items = screen.getAllByTestId('ordering-item');
    expect(items).toHaveLength(3);
    expect(orderingKeys()).toEqual(['A', 'B', 'C']);
    expect(orderingTexts()).toEqual(['第一项', '第二项', '第三项']);
    // Bounds: the first row cannot go up, the last cannot go down.
    expect(screen.getByRole('button', { name: '上移第 1 项' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '下移第 3 项' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '上移第 2 项' })).toBeEnabled();
    // No drag-only affordance: every move is a real focusable button.
    expect(screen.queryByTestId('ordering-drag')).not.toBeInTheDocument();
    // Nothing committed yet -> submit stays disabled instead of POSTing [].
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
    // The presented order is still a legal answer, via an explicit commit.
    expect(screen.getByRole('button', { name: '确认当前顺序' })).toBeEnabled();
  });

  it('announces the arrangement, because a moved row is otherwise silent (W17)', async () => {
    const user = userEvent.setup();
    render(<AnsweringPage question={question('ORDERING')} />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('第一项、第二项、第三项');
    expect(status).toHaveTextContent('尚未确认');

    // Moving a row only reorders the DOM, which assistive tech does not report
    // on its own; the live region is what tells a non-visual user where the row
    // ended up — and that the move also became the answer.
    await user.click(screen.getByRole('button', { name: '下移第 1 项' }));
    expect(status).toHaveTextContent('已提交顺序：第二项、第一项、第三项');
    expect(status).not.toHaveTextContent('尚未确认');
    // The commit affordance is gone once the order is settled, so the status is
    // the only thing left saying what happened — that is why it has to say it.
    expect(
      screen.queryByRole('button', { name: '确认当前顺序' })
    ).not.toBeInTheDocument();
  });

  it('emits 1-based positions of the presented options', async () => {
    const user = userEvent.setup();
    const emitted: number[][] = [];
    render(
      <AnsweringPage
        question={question('ORDERING')}
        onPayload={(payload) => emitted.push(payload.orderingAnswer ?? [])}
      />
    );

    // Row 1 down = option 2 in front of option 1: positions, not option keys.
    await user.click(screen.getByRole('button', { name: '下移第 1 项' }));
    expect(emitted).toEqual([[2, 1, 3]]);

    const submit = screen.getByRole('button', { name: '提交' });
    // Any committed permutation is complete, so submit is unlocked.
    expect(submit).toBeEnabled();
    // The rows track the emitted state, and no position is ever 0.
    expect(orderingKeys()).toEqual(['B', 'A', 'C']);
    expect(emitted.flatMap((value) => value).every((p) => p >= 1)).toBe(true);
  });

  it('builds the exact sequence [3,1,2] from a committed identity order', async () => {
    const user = userEvent.setup();
    const onOrderingAnswerChange = vi.fn();
    const { rerender } = render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        orderingAnswer={[1, 2, 3]}
        onOrderingAnswerChange={onOrderingAnswerChange}
      />
    );

    await user.click(screen.getByRole('button', { name: '上移第 3 项' }));
    expect(onOrderingAnswerChange).toHaveBeenLastCalledWith([1, 3, 2]);

    rerender(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        orderingAnswer={[1, 3, 2]}
        onOrderingAnswerChange={onOrderingAnswerChange}
      />
    );
    await user.click(screen.getByRole('button', { name: '上移第 2 项' }));
    expect(onOrderingAnswerChange).toHaveBeenLastCalledWith([3, 1, 2]);
  });

  it('commits the presented order when the user confirms it', async () => {
    const user = userEvent.setup();
    const emitted: number[][] = [];
    const onSubmit = vi.fn();
    render(
      <AnsweringPage
        question={question('ORDERING')}
        onPayload={(payload) => emitted.push(payload.orderingAnswer ?? [])}
        onSubmit={onSubmit}
      />
    );

    // Without a commit the answer cannot be sent at all.
    await user.click(screen.getByRole('button', { name: '提交' }));
    expect(onSubmit).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '确认当前顺序' }));
    expect(emitted).toEqual([[1, 2, 3]]);
    expect(screen.getByRole('button', { name: '提交' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '确认当前顺序' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '提交' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('moves an item with the keyboard alone', async () => {
    const user = userEvent.setup();
    const onOrderingAnswerChange = vi.fn();
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        onOrderingAnswerChange={onOrderingAnswerChange}
      />
    );

    await user.tab(); // first enabled control = 下移第 1 项
    expect(screen.getByRole('button', { name: '下移第 1 项' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onOrderingAnswerChange).toHaveBeenCalledWith([2, 1, 3]);
  });

  it('honours the order the page already holds', () => {
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        orderingAnswer={[3, 1, 2]}
        onOrderingAnswerChange={vi.fn()}
        onSubmit={vi.fn()}
      />
    );

    expect(orderingKeys()).toEqual(['C', 'A', 'B']);
    expect(orderingTexts()).toEqual(['第三项', '第一项', '第二项']);
    expect(screen.getByRole('button', { name: '提交' })).toBeEnabled();
  });

  it('refuses an incomplete ordering and repairs a drafted 0 instead of sending it', async () => {
    const user = userEvent.setup();
    const onOrderingAnswerChange = vi.fn();
    const onSubmit = vi.fn();
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        // A half-drafted sequence (and a 0, which the contract forbids with
        // `minimum: 1`): not an answer yet, so submit must stay dark.
        orderingAnswer={[0, 2]}
        onOrderingAnswerChange={onOrderingAnswerChange}
        onSubmit={onSubmit}
      />
    );

    const submit = screen.getByRole('button', { name: '提交' });
    expect(submit).toBeDisabled();
    await user.click(submit);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText('请先确定每个选项的位置。')).toBeInTheDocument();

    // The 0 is dropped rather than displayed or emitted and the missing
    // position is appended, so the rows read [2,1,3] and the next move produces
    // a legal 1-based permutation.
    expect(orderingKeys()).toEqual(['B', 'A', 'C']);
    await user.click(screen.getByRole('button', { name: '下移第 1 项' }));
    expect(onOrderingAnswerChange).toHaveBeenCalledWith([1, 2, 3]);
  });

  it('says so when the question has nothing to order', () => {
    render(
      <QuestionRenderer
        question={{ questionType: 'ORDERING', stem: '空题', options: [] }}
        mode="answer"
        onOrderingAnswerChange={vi.fn()}
        onSubmit={vi.fn()}
      />
    );
    expect(screen.getByTestId('ordering-no-options')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
  });

  it('review keeps the submitted order, drops the controls and flags the gap', () => {
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        answer={{ explanation: '按发生先后排列' }}
        mode="review"
        orderingAnswer={[2, 1, 3]}
      />
    );

    expect(
      screen.getAllByTestId('ordering-item').map((el) => el.textContent)
    ).toEqual(['1第二项', '2第一项', '3第三项']);
    expect(screen.queryByRole('button', { name: /上移|下移/ })).not.toBeInTheDocument();
    // C09b: the read side carries `correctOrder`, so the review surface owes the
    // user no "cannot be read back" notice any more.
    expect(screen.queryByTestId('contract-gap-notice')).not.toBeInTheDocument();
    expect(screen.getByTestId('question-explanation')).toBeInTheDocument();
  });

  it('review says so when nothing was submitted', () => {
    render(<QuestionRenderer question={question('ORDERING')} mode="review" />);
    expect(screen.getByTestId('ordering-empty')).toBeInTheDocument();
    // Printing the option order as if it were the user's answer would be a lie.
    expect(screen.queryAllByTestId('ordering-item')).toHaveLength(0);
  });
});

describe('QuestionRenderer — MATCHING (G02 / C09)', () => {
  it('splits the option list into the two columns the editor pairs', () => {
    const { left, right } = splitMatchingOptions(matchingOptions);
    expect(left.map((item) => item.optionKey)).toEqual(['A', 'B']);
    expect(right.map((item) => item.optionKey)).toEqual(['1', '2']);
  });

  it('emits the pair array as {left,right} JSON, keyed by optionKey', async () => {
    const user = userEvent.setup();
    const emitted: (string | undefined)[] = [];
    render(
      <AnsweringPage
        question={matchingQuestion()}
        onPayload={(payload) => emitted.push(payload.matchingAnswer)}
      />
    );

    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「巴黎」选择配对项' }),
      '1'
    );
    expect(emitted.at(-1)).toBe(JSON.stringify([{ left: 'A', right: '1' }]));

    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「东京」选择配对项' }),
      '2'
    );
    const wire = emitted.at(-1);
    expect(wire).toBe(
      JSON.stringify([
        { left: 'A', right: '1' },
        { left: 'B', right: '2' },
      ])
    );
    // Exactly the two keys the grader parses, nothing else.
    expect(JSON.parse(wire ?? '{}')).toEqual([
      { left: 'A', right: '1' },
      { left: 'B', right: '2' },
    ]);
  });

  it('keeps submit disabled until every left item is paired', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(
      <AnsweringPage question={matchingQuestion()} onSubmit={onSubmit} />
    );

    const submit = screen.getByRole('button', { name: '提交' });
    expect(submit).toBeDisabled();
    await user.click(submit);
    expect(onSubmit).not.toHaveBeenCalled();

    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「巴黎」选择配对项' }),
      '1'
    );
    expect(submit).toBeDisabled();
    expect(screen.getByText('请为每个左侧项目选择一个配对项。')).toBeInTheDocument();

    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「东京」选择配对项' }),
      '2'
    );
    expect(submit).toBeEnabled();
    await user.click(submit);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('reads back the stored pairs instead of starting over', () => {
    render(
      <QuestionRenderer
        question={matchingQuestion()}
        mode="answer"
        matchingAnswer={JSON.stringify([
          { left: 'B', right: '2' },
          { left: 'A', right: '1' },
        ])}
        onMatchingAnswerChange={vi.fn()}
        onSubmit={vi.fn()}
      />
    );

    // Order in the string is irrelevant (the grader compares sets), and both
    // rows come back filled, so the answer is submittable as it stands.
    expect(
      screen.getByRole('combobox', { name: '为「巴黎」选择配对项' })
    ).toHaveValue('1');
    expect(
      screen.getByRole('combobox', { name: '为「东京」选择配对项' })
    ).toHaveValue('2');
    expect(screen.getByRole('button', { name: '提交' })).toBeEnabled();
  });

  it('refuses a malformed or foreign pair string', () => {
    const onSubmit = vi.fn();
    const { rerender } = render(
      <QuestionRenderer
        question={matchingQuestion()}
        mode="answer"
        matchingAnswer="not json at all"
        onMatchingAnswerChange={vi.fn()}
        onSubmit={onSubmit}
      />
    );
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();

    rerender(
      <QuestionRenderer
        question={matchingQuestion()}
        mode="answer"
        // Complete-looking, but a pair the grader can never match (extra key)
        // and a right item this question does not offer.
        matchingAnswer={JSON.stringify([
          { left: 'A', right: '1', note: 'x' },
          { left: 'B', right: '9' },
        ])}
        onMatchingAnswerChange={vi.fn()}
        onSubmit={onSubmit}
      />
    );
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
    expect(parseMatchingPairs('{"left":"A"}', ['A'], ['1'])).toEqual([]);
  });

  it('shows the submitted pairs in review and never an editor', () => {
    render(
      <QuestionRenderer
        question={matchingQuestion()}
        answer={{ explanation: '首都是关键' }}
        mode="review"
        matchingAnswer={JSON.stringify([{ left: 'A', right: '1' }, { left: 'B', right: '2' }])}
      />
    );

    expect(screen.getAllByTestId('matching-pair').map((el) => el.textContent)).toEqual([
      '巴黎 ↔ 法国',
      '东京 ↔ 日本',
    ]);
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByTestId('contract-gap-notice')).not.toBeInTheDocument();
  });

  it('review says so when nothing was paired', () => {
    render(<QuestionRenderer question={matchingQuestion()} mode="review" />);
    expect(screen.getByTestId('matching-empty')).toBeInTheDocument();
    expect(screen.queryAllByTestId('matching-pair')).toHaveLength(0);
  });

  it('shows the two columns as the item pool when the page cannot hold pairs', () => {
    render(<QuestionRenderer question={matchingQuestion()} mode="answer" />);
    const columns = screen.getAllByTestId('matching-column');
    expect(columns).toHaveLength(2);
    expect(columns[0]).toHaveTextContent('巴黎');
    expect(columns[1]).toHaveTextContent('日本');
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });
});

describe('QuestionRenderer — existing call sites keep working', () => {
  it('single choice picks one key and submits', async () => {
    const user = userEvent.setup();
    const onSelectedOptionKeysChange = vi.fn();
    const onSubmit = vi.fn();
    render(
      <QuestionRenderer
        question={question('SINGLE_CHOICE')}
        mode="answer"
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
        onSubmit={onSubmit}
      />
    );

    await user.click(screen.getByRole('button', { name: '第一项' }));
    expect(onSelectedOptionKeysChange).toHaveBeenCalledWith(['A']);
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
  });

  it('multiple choice toggles keys without reordering them', async () => {
    const user = userEvent.setup();
    const onSelectedOptionKeysChange = vi.fn();
    render(
      <QuestionRenderer
        question={question('MULTIPLE_CHOICE')}
        mode="answer"
        selectedOptionKeys={['A']}
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
      />
    );

    await user.click(screen.getByRole('button', { name: '第三项' }));
    expect(onSelectedOptionKeysChange).toHaveBeenCalledWith(['A', 'C']);
  });

  it('true/false keeps its two buttons and short answer its textarea', () => {
    render(
      <QuestionRenderer
        question={{ questionType: 'TRUE_FALSE', stem: '地球是平的。' }}
        mode="answer"
        onBooleanAnswerChange={vi.fn()}
      />
    );
    expect(screen.getByRole('button', { name: '正确' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '错误' })).toBeEnabled();

    render(
      <QuestionRenderer
        question={{ questionType: 'SHORT_ANSWER', stem: '简述原因。' }}
        mode="answer"
        onTextAnswerChange={vi.fn()}
      />
    );
    expect(screen.getByRole('textbox', { name: '作答' })).toHaveValue('');
  });

  it('an unseen questionType still renders its options', () => {
    render(
      <QuestionRenderer question={question('DRAG_DROP')} mode="answer" />
    );
    expect(screen.getByRole('button', { name: '第一项' })).toBeEnabled();
    expect(screen.queryByTestId('contract-gap-notice')).not.toBeInTheDocument();
  });
});

describe('QuestionRenderer — selected state is spoken, not only coloured (W17)', () => {
  it('exposes which choice the learner picked through aria-pressed', async () => {
    const user = userEvent.setup();
    render(<AnsweringPage question={question('SINGLE_CHOICE')} />);

    expect(
      screen.getByRole('button', { name: '第一项' })
    ).toHaveAttribute('aria-pressed', 'false');

    await user.click(screen.getByRole('button', { name: '第二项' }));

    // Colour is the only other signal the button carries, so without this a
    // screen reader cannot tell a picked option from an unpicked one.
    expect(
      screen.getByRole('button', { name: '第二项' })
    ).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByRole('button', { name: '第一项' })
    ).toHaveAttribute('aria-pressed', 'false');
  });

  it('marks the standard answer by name in review, not only by colour', () => {
    render(
      <QuestionRenderer
        question={{
          questionType: 'SINGLE_CHOICE',
          stem: '题干内容',
          options: [
            { optionKey: 'A', content: '第一项', isCorrect: true },
            { optionKey: 'B', content: '第二项' },
          ],
        }}
        mode="review"
        selectedOptionKeys={['B']}
        answer={{ correctOptionKey: 'A', isCorrect: false }}
      />
    );

    // The learner's choice is `pressed`; the standard answer is named as such,
    // so the two colour roles (danger / primary) have a spoken equivalent.
    expect(
      screen.getByRole('button', { name: '第一项（标准答案）' })
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '第二项' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  it('marks the boolean choice the learner made', async () => {
    const user = userEvent.setup();
    const onBooleanAnswerChange = vi.fn();
    const { rerender } = render(
      <QuestionRenderer
        question={{ questionType: 'TRUE_FALSE', stem: '题干内容', options: [] }}
        mode="answer"
        onBooleanAnswerChange={onBooleanAnswerChange}
      />
    );

    await user.click(screen.getByRole('button', { name: '正确' }));
    expect(onBooleanAnswerChange).toHaveBeenCalledWith(true);

    // The renderer is controlled, so the pressed state follows the prop the page
    // holds — which is exactly the link that used to exist only as a colour.
    rerender(
      <QuestionRenderer
        question={{ questionType: 'TRUE_FALSE', stem: '题干内容', options: [] }}
        mode="answer"
        booleanAnswer
        onBooleanAnswerChange={onBooleanAnswerChange}
      />
    );
    expect(screen.getByRole('button', { name: '正确' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: '错误' })).toHaveAttribute(
      'aria-pressed',
      'false'
    );
  });
});
