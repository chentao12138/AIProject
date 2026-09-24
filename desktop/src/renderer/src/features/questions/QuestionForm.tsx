/**
 * Question Authoring (A4.4) — create only.
 *
 * There is deliberately no edit mode: the contract exposes
 * `POST /questions` and `POST /questions/{id}/publish` but no update
 * endpoint, so a "save" could only ever create a duplicate. See
 * BACKEND-CONTRACT-REQUESTS.md for the requested update verb.
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { NOT_FOUND_MESSAGE, normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { Button } from '../../components/Button';
import type { components } from '@aistudy/api-client';
type CreateQuestionRequest = components['schemas']['CreateQuestionRequest'];
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import {
  AUTHORABLE_QUESTION_TYPES,
  questionTypeLabel,
  type AuthorableQuestionType,
} from '../../lib/format';

interface OptionInput {
  optionKey: string;
  content: string;
}

export function QuestionForm() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();

  const [questionType, setQuestionType] = useState<AuthorableQuestionType>('SINGLE_CHOICE');
  const [stem, setStem] = useState('');
  const [explanation, setExplanation] = useState('');
  const [difficulty, setDifficulty] = useState('');
  const [options, setOptions] = useState<OptionInput[]>([
    { optionKey: 'A', content: '' },
    { optionKey: 'B', content: '' },
  ]);
  const [correctSingle, setCorrectSingle] = useState('');
  const [correctMultiple, setCorrectMultiple] = useState<string[]>([]);
  const [correctBoolean, setCorrectBoolean] = useState<boolean | null>(null);
  const [referenceAnswer, setReferenceAnswer] = useState('');
  const [knowledgePointIds, setKnowledgePointIds] = useState('');
  const [serverError, setServerError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () => {
      const body: CreateQuestionRequest = {
        questionType,
        stem,
        explanation: explanation || undefined,
        difficulty: difficulty || undefined,
        knowledgePointIds: knowledgePointIds
          ? knowledgePointIds.split(',').map((s) => Number(s.trim())).filter((n) => n > 0)
          : undefined,
      };
      if (questionType === 'SINGLE_CHOICE') {
        body.correctOptionKey = correctSingle || undefined;
        body.options = options.filter((o) => (o.content ?? '').trim()).map((o) => ({ optionKey: o.optionKey, content: o.content }));
      } else if (questionType === 'MULTIPLE_CHOICE') {
        body.correctOptionKeys = correctMultiple;
        body.options = options.filter((o) => (o.content ?? '').trim()).map((o) => ({ optionKey: o.optionKey, content: o.content }));
      } else if (questionType === 'TRUE_FALSE') {
        body.correctBoolean = correctBoolean ?? undefined;
      } else if (questionType === 'SHORT_ANSWER') {
        body.referenceAnswer = referenceAnswer || undefined;
      }
      return api
        .createQuestion(requireId(spaceId, 'space id'), body)
        .then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.questions(spaceId),
      });
      if (spaceId !== null) {
        navigate(`/spaces/${spaceId}/questions`);
      }
    },
    onError: (error) => {
      setServerError(normalizeApiError(error).message);
    },
  });

  if (spaceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  function addOption() {
    const nextKey = String.fromCharCode(65 + options.length);
    setOptions([...options, { optionKey: nextKey, content: '' }]);
  }

  function updateOptionKey(index: number, key: string) {
    const next = [...options];
    next[index] = { ...next[index], optionKey: key };
    setOptions(next);
  }

  function updateOptionContent(index: number, content: string) {
    const next = [...options];
    next[index] = { ...next[index], content };
    setOptions(next);
  }

  function removeOption(index: number) {
    setOptions(options.filter((_, i) => i !== index));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setServerError(null);
    create.mutate();
  }

  const busy = create.isPending;

  return (
    <div className="page">
      <PageHeader title="新建题目" />
      <form className="form" onSubmit={handleSubmit}>
        <div className="form__field">
          <label htmlFor="q-type" className="form__label">题型</label>
          <select
            id="q-type"
            className="select"
            value={questionType}
            onChange={(e) => setQuestionType(e.target.value as AuthorableQuestionType)}
          >
            {AUTHORABLE_QUESTION_TYPES.map((t) => (
              <option key={t} value={t}>{questionTypeLabel(t)}</option>
            ))}
          </select>
        </div>

        <div className="form__field">
          <label htmlFor="q-stem" className="form__label">题干</label>
          <textarea
            id="q-stem"
            className="textarea"
            value={stem}
            onChange={(e) => setStem(e.target.value)}
            rows={3}
            required
          />
        </div>

        {(questionType === 'SINGLE_CHOICE' || questionType === 'MULTIPLE_CHOICE') && (
          <div className="form__field">
            <label className="form__label">选项</label>
            {options.map((opt, idx) => (
              <div key={idx} className="form__row">
                <input
                  className="input form__row-key"
                  value={opt.optionKey}
                  onChange={(e) => updateOptionKey(idx, e.target.value)}
                  maxLength={1}
                />
                <input
                  className="input form__row-main"
                  value={opt.content}
                  onChange={(e) => updateOptionContent(idx, e.target.value)}
                  placeholder={`选项 ${opt.optionKey}`}
                />
                <Button
                  type="button"
                  variant="danger"
                  onClick={() => removeOption(idx)}
                  disabled={options.length <= 2}
                >
                  删除
                </Button>
              </div>
            ))}
            <Button type="button" variant="secondary" onClick={addOption}>
              添加选项
            </Button>
          </div>
        )}

        {questionType === 'SINGLE_CHOICE' && (
          <div className="form__field">
            <label htmlFor="q-correct" className="form__label">正确答案</label>
            <select
              id="q-correct"
              className="select"
              value={correctSingle}
              onChange={(e) => setCorrectSingle(e.target.value)}
            >
              <option value="">—</option>
              {options.map((o) => (
                <option key={o.optionKey} value={o.optionKey}>{o.optionKey}: {o.content || '（空）'}</option>
              ))}
            </select>
          </div>
        )}

        {questionType === 'MULTIPLE_CHOICE' && (
          <div className="form__field">
            <label className="form__label">正确选项（可多选）</label>
            <div className="form__checkboxes">
              {options.map((o) => (
                <label key={o.optionKey} className="form__checkbox">
                  <input
                    type="checkbox"
                    checked={correctMultiple.includes(o.optionKey)}
                    onChange={(e) => {
                      if (e.target.checked) {
                        setCorrectMultiple([...correctMultiple, o.optionKey]);
                      } else {
                        setCorrectMultiple(correctMultiple.filter((k) => k !== o.optionKey));
                      }
                    }}
                  />
                  {o.optionKey}: {o.content || '（空）'}
                </label>
              ))}
            </div>
          </div>
        )}

        {questionType === 'TRUE_FALSE' && (
          <div className="form__field">
            <label className="form__label">正确答案</label>
            <div className="form__radios">
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === true}
                  onChange={() => setCorrectBoolean(true)}
                />
                正确
              </label>
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === false}
                  onChange={() => setCorrectBoolean(false)}
                />
                错误
              </label>
            </div>
          </div>
        )}

        {questionType === 'SHORT_ANSWER' && (
          <div className="form__field">
            <label htmlFor="q-ref" className="form__label">参考答案</label>
            <textarea
              id="q-ref"
              className="textarea"
              value={referenceAnswer}
              onChange={(e) => setReferenceAnswer(e.target.value)}
              rows={2}
            />
          </div>
        )}

        <div className="form__field">
          <label htmlFor="q-explanation" className="form__label">解析</label>
          <textarea
            id="q-explanation"
            className="textarea"
            value={explanation}
            onChange={(e) => setExplanation(e.target.value)}
            rows={2}
          />
        </div>

        <div className="form__field">
          <label htmlFor="q-difficulty" className="form__label">难度</label>
          <select
            id="q-difficulty"
            className="select"
            value={difficulty}
            onChange={(e) => setDifficulty(e.target.value)}
          >
            <option value="">—</option>
            <option value="EASY">简单</option>
            <option value="MEDIUM">中等</option>
            <option value="HARD">困难</option>
          </select>
        </div>

        <div className="form__field">
          <label htmlFor="q-kp" className="form__label">关联知识点 ID（逗号分隔）</label>
          <input
            id="q-kp"
            className="input"
            value={knowledgePointIds}
            onChange={(e) => setKnowledgePointIds(e.target.value)}
            placeholder="例如：1, 2, 3"
          />
        </div>

        {serverError && (
          <p className="form__error" role="alert">{serverError}</p>
        )}

        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? '创建中…' : '创建'}
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate(`/spaces/${spaceId}/questions`)}>
            取消
          </Button>
        </div>
      </form>
    </div>
  );
}
