/**
 * Question List (A4.2, W08).
 *
 * /spaces/:spaceId/questions
 *
 * Server-driven filters, URL as the only holder of them: the query key and
 * the request read the same `searchParams`, so what you see in the address
 * bar is what was asked for. The contract's `GET /questions` accepts exactly
 * `status`, `questionType` and `knowledgePointId` — so those are the three
 * controls, and there is no pager: `page`/`size` do not exist in the contract
 * (C-Paging), and faking one by fetching everything and slicing locally is
 * forbidden. The gap is stated in the UI instead of being papered over.
 */

import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import { isPositiveId } from '../../lib/ids';
import {
  CONTRACT_GAP_NOTICES,
  difficultyLabel,
  questionTypeLabel,
  QUESTION_TYPES,
} from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function QuestionsPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const spaceId = useSpaceId();

  const status = searchParams.get('status') || undefined;
  const questionType = searchParams.get('questionType') || undefined;
  const rawKnowledgePointId = searchParams.get('knowledgePointId')
    ? Number(searchParams.get('knowledgePointId'))
    : undefined;
  const knowledgePointId = isPositiveId(rawKnowledgePointId)
    ? rawKnowledgePointId
    : undefined;

  const listQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.questions(spaceId, { status, questionType, knowledgePointId }),
      (id) =>
        api.listQuestions(id, {
          status,
          questionType,
          knowledgePointId,
        })
    )
  );

  /** Data source for the knowledge-point filter — the same list the
   *  knowledge page edits, so the filter can never offer a point that
   *  does not exist. */
  const knowledgePointsQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.knowledgePoints(spaceId),
      (id) => api.listKnowledgePoints(id)
    )
  );

  const items = listQuery.data ?? [];

  function setFilter(key: string, value: string | null) {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === '') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    setSearchParams(next, { replace: true });
  }

  const filterActive = Boolean(status || questionType || knowledgePointId);

  const points: Array<{ id: number; title: string }> = [];
  for (const kp of knowledgePointsQuery.data ?? []) {
    const kpId = kp.id;
    if (isPositiveId(kpId)) {
      points.push({ id: kpId, title: kp.title ?? `知识点 #${kpId}` });
    }
  }

  return (
    <div className="page">
      <PageHeader
        title="题库"
        actions={
          <Button
            variant="primary"
            onClick={() => navigate(`/spaces/${spaceId}/questions/new`)}
          >
            新建题目
          </Button>
        }
      />

      <section className="workbench-section" aria-label="筛选">
        <h2 className="section-title">筛选</h2>
        <div className="form">
          <div className="form__field">
            <label htmlFor="q-status" className="form__label">状态</label>
            <select
              id="q-status"
              className="select"
              value={status ?? ''}
              onChange={(e) => setFilter('status', e.target.value)}
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="PUBLISHED">已发布</option>
              {/* G09: `POST .../archive` writes status=ARCHIVED, and
                  `selectBySpaceOwnerFilters` only filters on the status it is
                  given (QuestionMapper.java:39-56), so an archived question is
                  reachable by asking for it — it is not hidden or deleted. */}
              <option value="ARCHIVED">已归档</option>
            </select>
          </div>
          <div className="form__field">
            <label htmlFor="q-type" className="form__label">题型</label>
            <select
              id="q-type"
              className="select"
              value={questionType ?? ''}
              onChange={(e) => setFilter('questionType', e.target.value)}
            >
              <option value="">全部</option>
              {QUESTION_TYPES.map((t) => (
                <option key={t} value={t}>{questionTypeLabel(t)}</option>
              ))}
            </select>
          </div>
          <div className="form__field">
            <label htmlFor="q-kp" className="form__label">知识点</label>
            <select
              id="q-kp"
              className="select"
              data-testid="kp-filter"
              value={knowledgePointId === undefined ? '' : String(knowledgePointId)}
              onChange={(e) => setFilter('knowledgePointId', e.target.value)}
            >
              <option value="">全部</option>
              {points.map((point) => (
                <option key={point.id} value={String(point.id)}>
                  {point.title}
                </option>
              ))}
            </select>
            {knowledgePointsQuery.isPending && (
              <p className="field__hint">知识点加载中…</p>
            )}
            {knowledgePointsQuery.isError && (
              <p className="field__hint">
                知识点列表加载失败，仍可手动用 URL 参数 knowledgePointId 筛选。
              </p>
            )}
          </div>
          {filterActive && (
            <Button
              type="button"
              variant="ghost"
              onClick={() => setSearchParams(new URLSearchParams(), { replace: true })}
            >
              清除筛选
            </Button>
          )}
        </div>
        <p className="page__note" role="note" data-testid="paging-gap-notice">
          {CONTRACT_GAP_NOTICES.QUESTION_PAGING}
        </p>
      </section>

      {listQuery.isPending && <LoadingState text="加载题目…" />}
      {listQuery.isError && (
        <ErrorState
          message={normalizeApiError(listQuery.error).message}
          onRetry={() => void listQuery.refetch()}
        />
      )}
      {listQuery.isSuccess && items.length === 0 && (
        <EmptyState title="还没有题目" description="先创建一道题，才能开始练习和考试。" />
      )}
      {listQuery.isSuccess && items.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>题型</th>
              <th>题干</th>
              <th>状态</th>
              <th>难度</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((q, idx) => {
              const archived = (q.status ?? '').trim().toUpperCase() === 'ARCHIVED';
              return (
                <tr key={q.id ?? `q-${idx}`}>
                  <td>#{q.id}</td>
                  <td>{questionTypeLabel(q.questionType)}</td>
                  <td>{q.stem?.slice(0, 80) ?? '—'}</td>
                  <td><StatusBadge status={q.status} /></td>
                  <td>{difficultyLabel(q.difficulty)}</td>
                  <td>
                    <Button
                      type="button"
                      onClick={() => spaceId !== null && navigate(`/spaces/${spaceId}/questions/${q.id}`)}
                    >
                      查看
                    </Button>
                    {/* G04: the row links to the edit form only when the server
                        would accept the PUT — `update()` answers 404 for an
                        ARCHIVED question (QuestionService.java:124), so an
                        archived row routes through 查看 to the restore button. */}
                    {!archived && q.id !== undefined && (
                      <Link
                        to={`/spaces/${spaceId}/questions/${q.id}/edit`}
                        className="nav-link"
                        data-testid={`question-edit-${q.id}`}
                      >
                        编辑
                      </Link>
                    )}
                    {archived && (
                      <span className="muted" data-testid={`question-archived-hint-${q.id}`}>
                        恢复请到详情页
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
