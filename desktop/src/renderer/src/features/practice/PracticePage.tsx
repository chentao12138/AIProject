/**
 * Practice Start (A5.1).
 *
 * /spaces/:spaceId/practice
 *
 * Creates a practice session and redirects to the active session.
 */

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function PracticePage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const { spaceId: spaceIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);

  const [count, setCount] = useState(10);
  const [knowledgePointId, setKnowledgePointId] = useState('');

  const kpQuery = useQuery({
    queryKey: queryKeys.knowledgePoints(spaceId ?? 0),
    queryFn: () => {
      if (spaceId === null) return Promise.reject(new Error('invalid space id'));
      return api.listKnowledgePoints(spaceId).then(unwrap);
    },
    enabled: spaceId !== null,
  });

  const start = useMutation({
    mutationFn: () => {
      if (spaceId === null) return Promise.reject(new Error('invalid space id'));
      const body: Record<string, unknown> = { count };
      const kpId = Number(knowledgePointId);
      if (kpId > 0) body.knowledgePointId = kpId;
      return api.createPracticeSession(spaceId, body).then(unwrap);
    },
    onSuccess: (session) => {
      if (session?.id) {
        navigate(`/spaces/${spaceId}/practice/${session.id}`);
      }
    },
  });

  if (!spaceId) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  return (
    <div className="page">
      <PageHeader title="Practice" />
      <section className="workbench-section" aria-label="Start practice">
        <h2 className="section-title">Start Practice</h2>
        <p className="page__note">
          Create a practice session to begin answering questions.
        </p>
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            start.mutate();
          }}
        >
          <div className="form__field">
            <label htmlFor="p-count" className="form__label">Number of Questions</label>
            <input
              id="p-count"
              className="input"
              type="number"
              min={1}
              max={50}
              value={count}
              onChange={(e) => setCount(Number(e.target.value))}
            />
          </div>
          <div className="form__field">
            <label htmlFor="p-kp" className="form__label">Focus Knowledge Point (optional)</label>
            <select
              id="p-kp"
              className="select"
              value={knowledgePointId}
              onChange={(e) => setKnowledgePointId(e.target.value)}
            >
              <option value="">All</option>
              {(kpQuery.data ?? []).map((kp) => (
                <option key={kp.id} value={String(kp.id)}>{kp.title ?? `#${kp.id}`}</option>
              ))}
            </select>
          </div>
          {start.isError && (
            <p className="form__error" role="alert">
              {normalizeApiError(start.error as Error).message}
            </p>
          )}
          <div className="form__actions">
            <Button type="submit" variant="primary" disabled={start.isPending}>
              {start.isPending ? 'Starting…' : 'Start Practice'}
            </Button>
          </div>
        </form>
      </section>

      <section className="workbench-section" aria-label="History">
        <h2 className="section-title">History</h2>
        <PracticeHistory spaceId={spaceId} />
      </section>
    </div>
  );
}

function PracticeHistory({ spaceId }: { spaceId: number }) {
  const api = useApiClient();
  const listQuery = useQuery({
    queryKey: queryKeys.practiceSessions(spaceId),
    queryFn: () => api.listPracticeSessions(spaceId).then(unwrap),
  });

  if (listQuery.isPending) return <LoadingState text="加载练习记录…" />;
  if (listQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(listQuery.error).message}
        onRetry={() => void listQuery.refetch()}
      />
    );
  }

  const items = listQuery.data ?? [];
  if (items.length === 0) {
    return <p className="muted">No practice sessions yet.</p>;
  }

  return (
    <table className="table">
      <thead>
        <tr>
          <th>Session</th>
          <th>Status</th>
          <th>Questions</th>
          <th>Started</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {items.map((s) => (
          <tr key={s.id}>
            <td>#{s.id}</td>
            <td><StatusBadge status={s.status} /></td>
            <td>{s.questionCount ?? '—'}</td>
            <td>{formatDateTime(s.startedAt)}</td>
            <td>
              <Button
                type="button"
                onClick={() => window.location.hash = `#/spaces/${spaceId}/practice/${s.id}`}
              >
                Open
              </Button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function formatDateTime(value?: string) {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString();
}
