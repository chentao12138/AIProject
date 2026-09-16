/**
 * AI Provider Settings page (FE-AI-001 Day 1).
 *
 * Architecture: Frontend = config UI only. Backend = key storage + all
 * StepFun requests. GET never returns the real key. SAVE omits apiKey
 * when unchanged. After save, key input state is cleared immediately.
 */

import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';

const PRESETS: Record<string, { baseUrl: string; model: string }> = {
  STEPFUN: { baseUrl: 'https://api.stepfun.com/v1', model: 'step-3.5-flash' },
};

interface TestResult {
  success?: boolean;
  provider?: string;
  model?: string;
  latencyMs?: number;
}

export function AiSettingsPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();

  const settingsQuery = useQuery({
    queryKey: queryKeys.aiSettings,
    queryFn: () => api.getAiSettings().then(unwrap),
  });

  const [enabled, setEnabled] = useState(false);
  const [provider, setProvider] = useState('OPENAI_COMPATIBLE');
  const [preset, setPreset] = useState('STEPFUN');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [keyDirty, setKeyDirty] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  useEffect(() => {
    const s = settingsQuery.data;
    if (!s) return;
    setEnabled(s.enabled ?? false);
    setProvider(s.provider ?? 'OPENAI_COMPATIBLE');
    setPreset(s.preset ?? 'STEPFUN');
    setBaseUrl(s.baseUrl ?? PRESETS.STEPFUN.baseUrl);
    setModel(s.model ?? PRESETS.STEPFUN.model);
  }, [settingsQuery.data]);

  function applyPreset(next: string) {
    setPreset(next);
    const p = PRESETS[next];
    if (p) {
      setBaseUrl(p.baseUrl);
      setModel(p.model);
    }
  }

  const save = useMutation({
    mutationFn: () => {
      const body: {
        enabled: boolean;
        provider: string;
        preset: string;
        baseUrl: string;
        model: string;
        apiKey?: string;
      } = { enabled, provider, preset, baseUrl, model };
      if (keyDirty && apiKey.trim() !== '') {
        body.apiKey = apiKey.trim();
      }
      return api.putAiSettings(body).then(unwrap);
    },
    onSuccess: () => {
      setApiKey('');
      setKeyDirty(false);
      setSaveMessage('设置已保存。');
      setSaveError(null);
      setTestResult(null);
      setTestError(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.aiSettings });
    },
    onError: (error) => {
      setSaveMessage(null);
      setSaveError(normalizeApiError(error).message);
    },
  });

  const testConnection = useMutation({
    mutationFn: () => api.testAiConnection().then(unwrap),
    onSuccess: (data) => {
      setTestResult(data as TestResult);
      setTestError(null);
    },
    onError: (error) => {
      setTestResult(null);
      setTestError(normalizeApiError(error).message);
    },
  });

  const deleteKey = useMutation({
    mutationFn: () => api.deleteAiApiKey().then(unwrap),
    onSuccess: () => {
      setApiKey('');
      setKeyDirty(false);
      setSaveMessage('API Key 已删除。');
      setSaveError(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.aiSettings });
    },
    onError: (error) => {
      setSaveError(normalizeApiError(error).message);
    },
  });

  if (settingsQuery.isPending) return <LoadingState text="加载 AI 设置…" />;
  if (settingsQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(settingsQuery.error).message}
        onRetry={() => void settingsQuery.refetch()}
      />
    );
  }

  const keyConfigured = settingsQuery.data?.apiKeyConfigured === true;

  return (
    <div className="page">
      <PageHeader title="AI Provider Settings" />
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="form__field">
          <label className="form__label" htmlFor="ai-enabled">
            <input
              id="ai-enabled"
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
            />{' '}
            启用 AI 功能
          </label>
        </div>

        <div className="form__field">
          <label className="form__label" htmlFor="ai-provider">Provider</label>
          <select
            id="ai-provider"
            className="select"
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value="OPENAI_COMPATIBLE">OpenAI Compatible</option>
          </select>
        </div>

        <div className="form__field">
          <label className="form__label" htmlFor="ai-preset">Preset</label>
          <select
            id="ai-preset"
            className="select"
            value={preset}
            onChange={(e) => applyPreset(e.target.value)}
          >
            <option value="STEPFUN">StepFun</option>
          </select>
        </div>

        <Input
          label="Base URL"
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
          placeholder="https://api.stepfun.com/v1"
        />
        <Input
          label="Model"
          value={model}
          onChange={(e) => setModel(e.target.value)}
          placeholder="step-3.5-flash"
        />

        <div className="form__field">
          <label className="form__label" htmlFor="ai-api-key">API Key</label>
          <input
            id="ai-api-key"
            type="password"
            className="input"
            value={apiKey}
            placeholder={keyConfigured ? '已配置（输入新 Key 以更换）' : '输入 API Key'}
            onChange={(e) => {
              setApiKey(e.target.value);
              setKeyDirty(true);
              setSaveMessage(null);
            }}
            autoComplete="off"
          />
          <p className="form__note" data-testid="key-status">
            {keyConfigured
              ? 'API Key 已配置。不修改则保存时保留现有 Key。'
              : '尚未配置 API Key。'}
          </p>
        </div>

        {saveError && (
          <p className="form__error" role="alert">{saveError}</p>
        )}
        {saveMessage && (
          <p className="form__note" role="status" data-testid="save-message">
            {saveMessage}
          </p>
        )}

        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={save.isPending}>
            {save.isPending ? '保存中…' : '保存'}
          </Button>
          <Button
            type="button"
            onClick={() => testConnection.mutate()}
            disabled={testConnection.isPending}
          >
            {testConnection.isPending ? '测试中…' : '测试连接'}
          </Button>
          {keyConfigured && (
            <Button
              type="button"
              onClick={() => deleteKey.mutate()}
              disabled={deleteKey.isPending}
            >
              {deleteKey.isPending ? '删除中…' : '删除 Key'}
            </Button>
          )}
        </div>
      </form>

      {testResult && (
        <div className="workbench-section" data-testid="test-result" role="status">
          <h2 className="section-title">连接测试</h2>
          <p className={testResult.success ? 'form__note' : 'form__error'}>
            {testResult.success
              ? `连接成功 · ${testResult.provider ?? ''} ${testResult.model ?? ''} · ${testResult.latencyMs ?? '?'}ms`
              : '连接失败，请检查配置。'}
          </p>
        </div>
      )}
      {testError && (
        <p className="form__error" role="alert" data-testid="test-error">
          {testError}
        </p>
      )}
    </div>
  );
}
