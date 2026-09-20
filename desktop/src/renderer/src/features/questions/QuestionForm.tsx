/**
 * Question Authoring (A4.4) — create only.
 *
 * There is deliberately no edit mode: the contract exposes
 * `POST /questions` and `POST /questions/{id}/publish` but no update
 * endpoint, so a "save" could only ever create a duplicate. See
 * BACKEND-CONTRACT-REQUESTS.md for the requested update verb.
 */

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { NOT_FOUND_MESSAGE, normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import type { components } from '@aistudy/api-client';
type CreateQuestionRequest = components['schemas']['CreateQuestionRequest'];
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';

type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'BOOLEAN' | 'TEXT_ANSWER';

interface OptionInput {
  optionKey: string;
  content: string;
}

export function QuestionForm() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { spaceId: spaceIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);

  const [questionType, setQuestionType] = useState<QuestionType>('SINGLE_CHOICE');
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
      if (spaceId === null) return Promise.reject(new Error('invalid space id'));
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
      } else if (questionType === 'BOOLEAN') {
        body.correctBoolean = correctBoolean ?? undefined;
      } else if (questionType === 'TEXT_ANSWER') {
        body.referenceAnswer = referenceAnswer || undefined;
      }
      return api.createQuestion(spaceId, body).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.questions(spaceId ?? 0) });
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
          <label htmlFor="q-type" className="form__label">Type</label>
          <select
            id="q-type"
            className="select"
            value={questionType}
            onChange={(e) => setQuestionType(e.target.value as QuestionType)}
          >
            <option value="SINGLE_CHOICE">Single Choice</option>
            <option value="MULTIPLE_CHOICE">Multiple Choice</option>
            <option value="BOOLEAN">Boolean</option>
            <option value="TEXT_ANSWER">Text Answer</option>
          </select>
        </div>

        <div className="form__field">
          <label htmlFor="q-stem" className="form__label">Stem</label>
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
            <label className="form__label">Options</label>
            {options.map((opt, idx) => (
              <div key={idx} className="form__row">
                <input
                  className="input"
                  value={opt.optionKey}
                  onChange={(e) => updateOptionKey(idx, e.target.value)}
                  style={{ width: '3rem' }}
                  maxLength={1}
                />
                <input
                  className="input"
                  value={opt.content}
                  onChange={(e) => updateOptionContent(idx, e.target.value)}
                  style={{ flex: 1 }}
                  placeholder={`Option ${opt.optionKey}`}
                />
                <Button
                  type="button"
                  variant="danger"
                  onClick={() => removeOption(idx)}
                  disabled={options.length <= 2}
                >
                  Remove
                </Button>
              </div>
            ))}
            <Button type="button" variant="secondary" onClick={addOption}>
              Add Option
            </Button>
          </div>
        )}

        {questionType === 'SINGLE_CHOICE' && (
          <div className="form__field">
            <label htmlFor="q-correct" className="form__label">Correct Option</label>
            <select
              id="q-correct"
              className="select"
              value={correctSingle}
              onChange={(e) => setCorrectSingle(e.target.value)}
            >
              <option value="">—</option>
              {options.map((o) => (
                <option key={o.optionKey} value={o.optionKey}>{o.optionKey}: {o.content || '(empty)'}</option>
              ))}
            </select>
          </div>
        )}

        {questionType === 'MULTIPLE_CHOICE' && (
          <div className="form__field">
            <label className="form__label">Correct Options</label>
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
                  {o.optionKey}: {o.content || '(empty)'}
                </label>
              ))}
            </div>
          </div>
        )}

        {questionType === 'BOOLEAN' && (
          <div className="form__field">
            <label className="form__label">Correct Answer</label>
            <div className="form__radios">
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === true}
                  onChange={() => setCorrectBoolean(true)}
                />
                True
              </label>
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === false}
                  onChange={() => setCorrectBoolean(false)}
                />
                False
              </label>
            </div>
          </div>
        )}

        {questionType === 'TEXT_ANSWER' && (
          <div className="form__field">
            <label htmlFor="q-ref" className="form__label">Reference Answer</label>
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
          <label htmlFor="q-explanation" className="form__label">Explanation</label>
          <textarea
            id="q-explanation"
            className="textarea"
            value={explanation}
            onChange={(e) => setExplanation(e.target.value)}
            rows={2}
          />
        </div>

        <div className="form__field">
          <label htmlFor="q-difficulty" className="form__label">Difficulty</label>
          <select
            id="q-difficulty"
            className="select"
            value={difficulty}
            onChange={(e) => setDifficulty(e.target.value)}
          >
            <option value="">—</option>
            <option value="EASY">Easy</option>
            <option value="MEDIUM">Medium</option>
            <option value="HARD">Hard</option>
          </select>
        </div>

        <div className="form__field">
          <label htmlFor="q-kp" className="form__label">Knowledge Point IDs (comma-separated)</label>
          <input
            id="q-kp"
            className="input"
            value={knowledgePointIds}
            onChange={(e) => setKnowledgePointIds(e.target.value)}
            placeholder="e.g. 1, 2, 3"
          />
        </div>

        {serverError && (
          <p className="form__error" role="alert">{serverError}</p>
        )}

        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? 'Creating…' : 'Create'}
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate(`/spaces/${spaceId}/questions`)}>
            Cancel
          </Button>
        </div>
      </form>
    </div>
  );
}
