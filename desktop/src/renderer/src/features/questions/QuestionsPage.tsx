/**
 * Question List (A4.2, W08).
 *
 * /spaces/:spaceId/questions
 *
 * Server-driven filters, URL as the only holder of them: the query key and
 * the request read the same `searchParams`, so what you see in the address
 * bar is what was asked for. The contract's `GET /questions` (per the
 * generated api-client + OpenAPI) accepts `status`, `questionType`,
 * `knowledgePointId`, `page` and `size` — so a real, server-side pager is
 * rendered and `page` is a first-class filter. Difficulty and keyword are NOT
 * in the contract's query params, so no difficulty/keyword control is shown
 * and faking one by fetching everything and slicing locally is forbidden;
 * those two filters remain a backend contract gap (C-Paging / C-filter), not
 * a missing UI we papered over.
 */

import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import { isPositiveId } from '../../lib/ids';
import {
  QUESTION_STATUSES,
  difficultyLabel,
  isQuestionEditable,
  questionTypeLabel,
  QUESTIONS_PAGE_SIZE,
  QUESTION_TYPES,
} from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { SelectMenu } from '../../components/SelectMenu';
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
  // 0-based, like the server's own `page`. A garbage value falls back to the
  // first page instead of requesting a page that cannot exist.
  const rawPage = Number(searchParams.get('page') ?? '0');
  const page = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0;

  const listQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.questions(spaceId, {
        status,
        questionType,
        knowledgePointId,
        page,
        size: QUESTIONS_PAGE_SIZE,
      }),
      (id) =>
        api.listQuestions(id, {
          status,
          questionType,
          knowledgePointId,
          page,
          size: QUESTIONS_PAGE_SIZE,
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

  const pageView = listQuery.data;
  const items = pageView?.items ?? [];
  const totalItems = pageView?.totalItems ?? 0;
  const totalPages = pageView?.totalPages ?? 0;

  // The URL is the pager's only state, so `?page=99` of a 2-page list would
  // otherwise render 第 100 / 2 页 over an empty table: the server answers that
  // page with no items, and nothing in the page corrects the address. Once the
  // real page count is known, rewrite the address to the last page that exists
  // (replace, so a self-inflicted URL does not become a history entry).
  useEffect(() => {
    if (totalPages === 0 || page < totalPages) {
      return;
    }
    const next = new URLSearchParams(searchParams);
    next.set('page', String(totalPages - 1));
    setSearchParams(next, { replace: true });
  }, [totalPages, page, searchParams, setSearchParams]);

  function setFilter(key: string, value: string | null) {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === '') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    // A changed filter is a different result set: its page numbers no longer
    // mean the same thing, and landing on page 3 of a shorter list looked like
    // an empty page rather than a filtered one.
    next.delete('page');
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
        <div className="form form--inline">
          <SelectMenu
            label="状态"
            value={status ?? ''}
            onValueChange={(value) => setFilter('status', value)}
            options={[
              { value: '', label: '全部' },
              { value: 'DRAFT', label: '草稿' },
              { value: 'PUBLISHED', label: '已发布' },
              // G09: `POST .../archive` writes status=ARCHIVED, and
              // `selectBySpaceOwnerFilters` only filters on the status it is
              // given (QuestionMapper.java:39-56), so an archived question is
              // reachable by asking for it — it is not hidden or deleted.
              { value: 'ARCHIVED', label: '已归档' },
            ]}
          />
          <SelectMenu
            label="题型"
            value={questionType ?? ''}
            onValueChange={(value) => setFilter('questionType', value)}
            options={[
              { value: '', label: '全部' },
              ...QUESTION_TYPES.map((type) => ({
                value: type,
                label: questionTypeLabel(type),
              })),
            ]}
          />
          <SelectMenu
            label="知识点"
            testId="kp-filter"
            value={knowledgePointId === undefined ? '' : String(knowledgePointId)}
            onValueChange={(value) => setFilter('knowledgePointId', value)}
            hint={
              knowledgePointsQuery.isPending
                ? '知识点加载中…'
                : knowledgePointsQuery.isError
                  ? '知识点列表加载失败，仍可手动用 URL 参数 knowledgePointId 筛选。'
                  : undefined
            }
            options={[
              { value: '', label: '全部' },
              ...points.map((point) => ({
                value: String(point.id),
                label: point.title,
              })),
            ]}
          />
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
        <div className="pager">
        <p className="page__note" data-testid="questions-total">
          第 {page + 1} / {Math.max(totalPages, 1)} 页 · 共 {totalItems} 条 ·
          每页 {QUESTIONS_PAGE_SIZE} 条
        </p>
        {totalPages > 1 && (
          <div className="form__actions">
            <Button
              type="button"
              variant="secondary"
              disabled={page === 0 || listQuery.isFetching}
              data-testid="questions-prev-page"
              onClick={() => {
                const next = new URLSearchParams(searchParams);
                next.set('page', String(page - 1));
                setSearchParams(next);
              }}
            >
              上一页
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={page >= totalPages - 1 || listQuery.isFetching}
              data-testid="questions-next-page"
              onClick={() => {
                const next = new URLSearchParams(searchParams);
                next.set('page', String(page + 1));
                setSearchParams(next);
              }}
            >
              下一页
            </Button>
          </div>
        )}
        </div>
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
              const normalizedStatus = (q.status ?? '').trim().toUpperCase();
              const archived = normalizedStatus === QUESTION_STATUSES.ARCHIVED;
              const editable = isQuestionEditable(q.status);
              return (
                <tr key={q.id ?? `q-${idx}`}>
                  <td>#{q.id}</td>
                  <td>{questionTypeLabel(q.questionType)}</td>
                  <td>{q.stem?.slice(0, 80) ?? '—'}</td>
                  <td><StatusBadge status={q.status} /></td>
                  <td>{difficultyLabel(q.difficulty)}</td>
                  <td>
                    <div className="row-actions">
                    <Button
                      type="button"
                      onClick={() => spaceId !== null && navigate(`/spaces/${spaceId}/questions/${q.id}`)}
                    >
                      查看
                    </Button>
                    {/* G04: the row links to the edit form only when the server
                        would accept the PUT. The contract's PUT description
                        allows a DRAFT only and answers 409
                        `QUESTION_NOT_EDITABLE` for PUBLISHED / ARCHIVED /
                        REJECTED, so those rows route through 查看 instead. */}
                    {editable && q.id !== undefined && (
                      <Link
                        to={`/spaces/${spaceId}/questions/${q.id}/edit`}
                        className="nav-link"
                        data-testid={`question-edit-${q.id}`}
                      >
                        编辑
                      </Link>
                    )}
                    {!editable && q.id !== undefined && (
                      <span className="muted" data-testid={`question-not-editable-hint-${q.id}`}>
                        只有草稿可编辑
                      </span>
                    )}
                    {archived && (
                      <span className="muted" data-testid={`question-archived-hint-${q.id}`}>
                        恢复请到详情页
                      </span>
                    )}
                    </div>
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
