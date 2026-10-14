/**
 * UsersPage (FE-ADMIN-001): paged user directory + per-user role
 * replacement and ACTIVE/DISABLED status changes. Every mutation goes
 * through admin-only backend endpoints; the backend is the authority —
 * this UI is an operator convenience (ADR-028).
 */

import { useState } from 'react';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { useApiClient } from '../../lib/api-context';
import { unwrap } from '../../lib/api-error';

type AdminUserPage = NonNullable<
  Awaited<ReturnType<ApiClient['adminListUsers']>>['data']
>;
type AdminUserSummary = NonNullable<AdminUserPage['items']>[number];
type AdminUserDetail = NonNullable<
  Awaited<ReturnType<ApiClient['adminGetUser']>>['data']
>;

const PAGE_SIZE = 20;

function formatDate(value?: string): string {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleString();
}

export function UsersPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [selectedSubject, setSelectedSubject] = useState<string | null>(null);
  const [roleDraft, setRoleDraft] = useState('');

  const listQuery = useQuery({
    queryKey: ['admin', 'users', page],
    queryFn: () =>
      api.adminListUsers({ page, size: PAGE_SIZE }).then(unwrap),
  });

  const detailQuery = useQuery({
    queryKey: ['admin', 'user', selectedSubject],
    queryFn: () =>
      api.adminGetUser(selectedSubject as string).then(unwrap),
    enabled: selectedSubject !== null,
  });

  const rolesMutation = useMutation({
    mutationFn: (roles: string[]) =>
      api.adminUpdateRoles(selectedSubject as string, { roles }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin'] });
    },
  });

  const statusMutation = useMutation({
    mutationFn: (status: 'ACTIVE' | 'DISABLED') =>
      api.adminUpdateStatus(selectedSubject as string, { status }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin'] });
    },
  });

  function selectUser(user: AdminUserSummary) {
    setSelectedSubject(user.subject ?? null);
    setRoleDraft((user.roles ?? []).join(', '));
  }

  const pageData = listQuery.data;
  const detail: AdminUserDetail | undefined = detailQuery.data;

  return (
    <section className="page">
      <div className="page__header">
        <h1 className="page__title">用户管理</h1>
      </div>

      {listQuery.isPending && <p className="loading">加载用户列表…</p>}
      {listQuery.isError && (
        <div className="error-state" role="alert">
          <p className="error-state__message">用户列表加载失败。</p>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={() => void listQuery.refetch()}
          >
            重试
          </button>
        </div>
      )}

      {listQuery.isSuccess && pageData && (
        <>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>用户名</th>
                  <th>Subject</th>
                  <th>状态</th>
                  <th>角色</th>
                  <th>创建时间</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {(pageData.items ?? []).map((user) => (
                  <tr
                    key={user.subject ?? String(user.id)}
                    className={
                      selectedSubject === user.subject
                        ? 'data-table__row--selected'
                        : ''
                    }
                  >
                    <td>{user.id ?? '—'}</td>
                    <td>{user.username ?? '—'}</td>
                    <td className="mono">{user.subject ?? '—'}</td>
                    <td>
                      <span
                        className={`badge badge--${(user.status ?? '').toLowerCase()}`}
                      >
                        {user.status ?? '—'}
                      </span>
                    </td>
                    <td>{(user.roles ?? []).join(', ') || '—'}</td>
                    <td>{formatDate(user.createdAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn btn--ghost"
                        onClick={() => selectUser(user)}
                      >
                        管理
                      </button>
                    </td>
                  </tr>
                ))}
                {(pageData.items ?? []).length === 0 && (
                  <tr>
                    <td colSpan={7} className="table-empty">
                      没有用户
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="pager">
            <button
              type="button"
              className="btn btn--secondary"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </button>
            <span className="pager__info">
              第 {page + 1} / {Math.max(1, pageData.totalPages ?? 1)} 页 · 共{' '}
              {pageData.totalElements ?? 0} 人
            </span>
            <button
              type="button"
              className="btn btn--secondary"
              disabled={page + 1 >= (pageData.totalPages ?? 1)}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </button>
          </div>
        </>
      )}

      {selectedSubject && (
        <div className="card detail-panel" aria-label="用户详情与管理">
          <h2 className="detail-panel__title">
            管理用户：{detail?.username ?? selectedSubject}
          </h2>
          {detailQuery.isPending && <p className="loading">加载详情…</p>}
          {detailQuery.isError && (
            <p className="form-error" role="alert">
              用户详情加载失败。
            </p>
          )}
          {detail && (
            <>
              <p className="detail-panel__meta mono">
                subject: {detail.subject} · 状态：{detail.status}
              </p>
              <label className="field">
                <span className="field__label">
                  角色列表（逗号分隔，如 ROLE_USER, ROLE_ADMIN）
                </span>
                <input
                  className="input"
                  value={roleDraft}
                  onChange={(e) => setRoleDraft(e.target.value)}
                />
              </label>
              <div className="detail-panel__actions">
                <button
                  type="button"
                  className="btn btn--primary"
                  disabled={rolesMutation.isPending}
                  onClick={() => {
                    const roles = roleDraft
                      .split(',')
                      .map((r) => r.trim())
                      .filter((r) => r.length > 0);
                    rolesMutation.mutate(roles);
                  }}
                >
                  {rolesMutation.isPending ? '保存中…' : '保存角色'}
                </button>
                <button
                  type="button"
                  className="btn btn--secondary"
                  disabled={statusMutation.isPending}
                  onClick={() =>
                    statusMutation.mutate(
                      detail.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
                    )
                  }
                >
                  {detail.status === 'DISABLED' ? '启用账号' : '停用账号'}
                </button>
                {rolesMutation.isSuccess && (
                  <span className="form-ok" role="status">
                    角色已更新
                  </span>
                )}
                {statusMutation.isSuccess && (
                  <span className="form-ok" role="status">
                    状态已更新
                  </span>
                )}
                {(rolesMutation.isError || statusMutation.isError) && (
                  <span className="form-error" role="alert">
                    操作失败，请检查权限后重试。
                  </span>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}
