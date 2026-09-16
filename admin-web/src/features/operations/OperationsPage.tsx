/**
 * OperationsPage (FE-ADMIN-001): orphan-storage reconciliation.
 *
 * DRY_RUN reports candidates without deleting; CLEANUP actually removes
 * orphan blobs. The destructive action is visually separated and
 * requires a second click (two-step confirm) to prevent accidental
 * deletion.
 */

import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { useApiClient } from '../../lib/api-context';
import { unwrap } from '../../lib/api-error';

type ReconcileResult = NonNullable<
  Awaited<ReturnType<ApiClient['adminReconcileStorage']>>['data']
>;

const RESULT_FIELDS: Array<{ key: keyof ReconcileResult; label: string }> = [
  { key: 'scanned', label: '扫描对象' },
  { key: 'referenced', label: '被引用' },
  { key: 'candidateOrphans', label: '候选孤儿' },
  { key: 'deleted', label: '实际删除' },
  { key: 'skippedRecent', label: '近期跳过' },
  { key: 'skippedUnsafe', label: '不安全跳过' },
];

export function OperationsPage() {
  const api = useApiClient();
  const [confirmCleanup, setConfirmCleanup] = useState(false);
  const [result, setResult] = useState<ReconcileResult | null>(null);

  const reconcile = useMutation({
    mutationFn: (mode: 'DRY_RUN' | 'CLEANUP') =>
      api.adminReconcileStorage({ mode }).then(unwrap),
    onSuccess: (data) => {
      setResult(data);
      setConfirmCleanup(false);
    },
  });

  return (
    <section className="page">
      <div className="page__header">
        <h1 className="page__title">存储运维</h1>
      </div>

      <div className="card">
        <h2 className="detail-panel__title">孤儿文件对账</h2>
        <p className="login-card__hint">
          DRY_RUN 只扫描不删除；CLEANUP 会真正删除未被引用的存储对象。
        </p>
        <div className="detail-panel__actions">
          <button
            type="button"
            className="btn btn--secondary"
            disabled={reconcile.isPending}
            onClick={() => reconcile.mutate('DRY_RUN')}
          >
            {reconcile.isPending ? '扫描中…' : '执行 DRY_RUN 扫描'}
          </button>

          {!confirmCleanup ? (
            <button
              type="button"
              className="btn btn--danger"
              disabled={reconcile.isPending}
              onClick={() => setConfirmCleanup(true)}
            >
              CLEANUP 清理…
            </button>
          ) : (
            <>
              <button
                type="button"
                className="btn btn--danger"
                disabled={reconcile.isPending}
                onClick={() => reconcile.mutate('CLEANUP')}
              >
                再次点击确认删除孤儿文件
              </button>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => setConfirmCleanup(false)}
              >
                取消
              </button>
            </>
          )}
        </div>
        {reconcile.isError && (
          <p className="form-error" role="alert">
            对账执行失败，请检查管理员权限或后端状态。
          </p>
        )}
      </div>

      {result && (
        <div className="card result-panel" role="status">
          <h2 className="detail-panel__title">
            对账结果（模式：{result.mode ?? '—'}
            {result.truncated ? '，结果已截断' : ''}）
          </h2>
          <div className="result-grid">
            {RESULT_FIELDS.map(({ key, label }) => (
              <div className="result-cell" key={String(key)}>
                <div className="result-cell__value">
                  {result[key] ?? 0}
                </div>
                <div className="result-cell__label">{label}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}
