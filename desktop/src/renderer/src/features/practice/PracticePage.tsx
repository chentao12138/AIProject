/**
 * Practice Start (A5.1).
 *
 * /spaces/:spaceId/practice
 *
 * Creates a practice session and redirects to the active session.
 */

import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId, requireId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { parseOptionalPositiveId } from '../../lib/ids';
import { formatDateTime } from '../../lib/format';
import type { CreatePracticeSessionBody } from '../../lib/types';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function PracticePage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();
  const [searchParams, setSearchParams] = useSearchParams();

  const [count, setCount] = useState(10);
  // W16/A9.2: arriving from a question or knowledge point passes the id in the
  // URL, so it is the source of truth for the selector instead of component
  // state that a link could never reach.
  const knowledgePointId = searchParams.get('knowledgePointId') ?? '';

  function setKnowledgePointId(value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set('knowledgePointId', value);
    } else {
      next.delete('knowledgePointId');
    }
    setSearchParams(next);
  }

  const kpQuery = useQuery(
    spaceQuery(spaceId, queryKeys.knowledgePoints(spaceId), (id) =>
      api.listKnowledgePoints(id)
    )
  );

  const start = useMutation({
    mutationFn: () => {
      const kpId = parseOptionalPositiveId(knowledgePointId);
      const body: CreatePracticeSessionBody = {
        count,
        knowledgePointId: kpId,
      };
      return api
        .createPracticeSession(requireId(spaceId, 'space id'), body)
        .then(unwrap);
    },
    onSuccess: (session) => {
      if (session?.id) {
        navigate(`/spaces/${spaceId}/practice/${session.id}`);
      }
    },
  });

  if (!spaceId) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  return (
    <div className="page">
      <PageHeader title="练习" />
      <section className="workbench-section" aria-label="开始练习">
        <h2 className="section-title">开始练习</h2>
        <p className="page__note">
          创建一次练习会话，然后逐题作答。
        </p>
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            start.mutate();
          }}
        >
          <div className="form__field">
            <label htmlFor="p-count" className="form__label">题目数量</label>
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
            <label htmlFor="p-kp" className="form__label">限定知识点（可选）</label>
            <select
              id="p-kp"
              className="select"
              value={knowledgePointId}
              onChange={(e) => setKnowledgePointId(e.target.value)}
            >
              <option value="">全部</option>
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
              {start.isPending ? '创建中…' : '开始练习'}
            </Button>
          </div>
        </form>
      </section>

      <section className="workbench-section" aria-label="练习历史">
        <h2 className="section-title">练习历史</h2>
        <PracticeHistory spaceId={spaceId} />
      </section>
    </div>
  );
}

function PracticeHistory({ spaceId }: { spaceId: number }) {
  const api = useApiClient();
  const navigate = useNavigate();
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
    return <p className="muted">还没有练习记录。</p>;
  }

  return (
    <table className="table">
      <thead>
        <tr>
          <th>会话</th>
          <th>状态</th>
          <th>题目数</th>
          <th>开始时间</th>
          <th>操作</th>
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
                onClick={() => navigate(`/spaces/${spaceId}/practice/${s.id}`)}
              >
                打开
              </Button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
