/**
 * QuestionRenderer — the three types G02 added (FILL_BLANK / ORDERING /
 * MATCHING), plus the behavior the exams and practice pages already depend on.
 *
 * ORDERING is the interesting one for a component test: it is a *controlled*
 * order. The component never mutates `selectedOptionKeys` silently — it emits
 * the reordered array and lets the page own the state — and the order travels
 * in the one contract field that has an order.
 *
 * MATCHING is asserted by what it must NOT do: no invented pair widgets, no
 * submit button for a payload the contract cannot carry.
 */

import { describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  QuestionRenderer,
  type QuestionPayload,
} from './QuestionRenderer';

/**
 * A page holding the answer state, because the renderer is fully controlled:
 * typing into an unheld textarea would just re-emit one character per
 * keystroke, which is a property of the component worth pinning.
 */
function AnsweringPage({
  question,
  onSubmit = () => {},
  submitLabel,
}: {
  question: QuestionPayload;
  onSubmit?: () => void;
  submitLabel?: string;
}) {
  const [selectedOptionKeys, setSelectedOptionKeys] = useState<string[]>([]);
  const [textAnswer, setTextAnswer] = useState('');
  return (
    <QuestionRenderer
      question={question}
      mode="answer"
      selectedOptionKeys={selectedOptionKeys}
      textAnswer={textAnswer}
      onSelectedOptionKeysChange={setSelectedOptionKeys}
      onTextAnswerChange={setTextAnswer}
      onSubmit={onSubmit}
      submitLabel={submitLabel}
    />
  );
}

const choiceOptions = [
  { optionKey: 'A', content: '第一项' },
  { optionKey: 'B', content: '第二项' },
  { optionKey: 'C', content: '第三项' },
];

function question(questionType: string): QuestionPayload {
  return { questionType, stem: '题干内容', options: choiceOptions };
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

describe('QuestionRenderer — ORDERING (G02)', () => {
  it('renders the items as a keyboard-operable ordered list', () => {
    const onSelectedOptionKeysChange = vi.fn();
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
        onSubmit={vi.fn()}
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
    // Submit waits for an explicit order instead of sending an empty one.
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
  });

  it('emits the swapped order through selectedOptionKeys', async () => {
    const user = userEvent.setup();
    const onSelectedOptionKeysChange = vi.fn();
    const { rerender } = render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
      />
    );

    await user.click(screen.getByRole('button', { name: '下移第 1 项' }));
    expect(onSelectedOptionKeysChange).toHaveBeenCalledWith(['B', 'A', 'C']);

    // Controlled: the page owns the order, so the next swap is applied to the
    // array it stored rather than to the rendered default.
    rerender(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        selectedOptionKeys={['B', 'A', 'C']}
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
      />
    );
    await user.click(screen.getByRole('button', { name: '上移第 3 项' }));
    expect(onSelectedOptionKeysChange).toHaveBeenLastCalledWith(['B', 'C', 'A']);
  });

  it('moves an item with the keyboard alone', async () => {
    const user = userEvent.setup();
    const onSelectedOptionKeysChange = vi.fn();
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        onSelectedOptionKeysChange={onSelectedOptionKeysChange}
      />
    );

    await user.tab(); // first enabled control = 下移第 1 项
    expect(screen.getByRole('button', { name: '下移第 1 项' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onSelectedOptionKeysChange).toHaveBeenCalledWith(['B', 'A', 'C']);
  });

  it('honours the order the page already holds', () => {
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        mode="answer"
        selectedOptionKeys={['C', 'A', 'B']}
        onSelectedOptionKeysChange={vi.fn()}
        onSubmit={vi.fn()}
      />
    );

    expect(orderingKeys()).toEqual(['C', 'A', 'B']);
    expect(orderingTexts()).toEqual(['第三项', '第一项', '第二项']);
    expect(screen.getByRole('button', { name: '提交' })).toBeEnabled();
  });

  it('review keeps the submitted order, drops the controls and flags the gap', () => {
    render(
      <QuestionRenderer
        question={question('ORDERING')}
        answer={{ explanation: '按发生先后排列' }}
        mode="review"
        selectedOptionKeys={['B', 'A', 'C']}
      />
    );

    expect(
      screen.getAllByTestId('ordering-item').map((el) => el.textContent)
    ).toEqual(['1第二项', '2第一项', '3第三项']);
    expect(screen.queryByRole('button', { name: /上移|下移/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent(
      'QuestionAnswerView'
    );
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent('C09');
    expect(screen.getByTestId('question-explanation')).toBeInTheDocument();
  });

  it('review says so when nothing was submitted', () => {
    render(<QuestionRenderer question={question('ORDERING')} mode="review" />);
    expect(screen.getByTestId('ordering-empty')).toBeInTheDocument();
    // Printing the option order as if it were the user's answer would be a lie.
    expect(screen.queryAllByTestId('ordering-item')).toHaveLength(0);
  });
});

describe('QuestionRenderer — MATCHING (G02)', () => {
  it('renders the options read-only in two columns', () => {
    render(<QuestionRenderer question={question('MATCHING')} mode="review" />);

    const columns = screen.getAllByTestId('matching-column');
    expect(columns).toHaveLength(2);
    expect(columns[0]).toHaveTextContent('第一项');
    expect(columns[0]).toHaveTextContent('第二项');
    expect(columns[1]).toHaveTextContent('第三项');
  });

  it('offers no pair widget and no submit in answer mode', () => {
    const onSubmit = vi.fn();
    render(
      <QuestionRenderer
        question={question('MATCHING')}
        mode="answer"
        onSelectedOptionKeysChange={vi.fn()}
        onSubmit={onSubmit}
      />
    );

    expect(screen.queryByRole('button', { name: '提交' })).not.toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent('配对题');
    expect(screen.getByTestId('contract-gap-notice')).toHaveTextContent('pairs');
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
