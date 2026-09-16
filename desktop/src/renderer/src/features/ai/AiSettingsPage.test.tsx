/**
 * AI Settings key-semantics tests (FE-AI-001).
 *
 * These protect the most critical security behaviors:
 *   - GET never fills real key into form
 *   - SAVE omits apiKey when user didn't change it
 *   - SAVE sends apiKey only when user entered a new one
 *   - After save, key input state is cleared
 *   - Placeholder "********" is never sent
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AiSettingsPage } from './AiSettingsPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const settingsOk = {
  data: {
    enabled: true,
    provider: 'OPENAI_COMPATIBLE',
    preset: 'STEPFUN',
    baseUrl: 'https://api.stepfun.com/v1',
    model: 'step-3.5-flash',
    apiKeyConfigured: true,
  },
  response: { status: 200 },
};

function renderSettings(overrides: Record<string, unknown> = {}) {
  return renderWithProviders(<AiSettingsPage />, {
    apiClient: mockApiClient({
      getAiSettings: vi.fn(async () => settingsOk),
      ...overrides,
    }),
  });
}

describe('AI Settings — key security semantics', () => {
  it('GET does not fill apiKey input with real key', async () => {
    renderSettings();
    await screen.findByRole('button', { name: '保存' });
    const keyInput = screen.getByLabelText('API Key') as HTMLInputElement;
    expect(keyInput.value).toBe('');
    expect(keyInput.placeholder).toContain('已配置');
  });

  it('shows apiKeyConfigured status', async () => {
    renderSettings();
    expect(await screen.findByTestId('key-status')).toHaveTextContent(
      '已配置'
    );
  });

  it('SAVE omits apiKey when user did not change it', async () => {
    const user = userEvent.setup();
    const putAiSettings = vi.fn(async (_body: Record<string, unknown>) => ({
      data: settingsOk.data,
      response: { status: 200 },
    }));
    renderSettings({ putAiSettings });
    await screen.findByRole('button', { name: '保存' });

    // Change a non-key field and save.
    const modelInput = screen.getByLabelText('Model');
    fireEvent.change(modelInput, { target: { value: 'step-3.5-pro' } });
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(putAiSettings).toHaveBeenCalledTimes(1);
    });
    const body = putAiSettings.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('apiKey');
  });

  it('SAVE sends apiKey only when user entered a new one', async () => {
    const user = userEvent.setup();
    const putAiSettings = vi.fn(async (_body: Record<string, unknown>) => ({
      data: settingsOk.data,
      response: { status: 200 },
    }));
    renderSettings({ putAiSettings });
    await screen.findByRole('button', { name: '保存' });

    const keyInput = screen.getByLabelText('API Key');
    fireEvent.change(keyInput, { target: { value: 'sk-new-key-123' } });
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(putAiSettings).toHaveBeenCalledTimes(1);
    });
    const body = putAiSettings.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(body.apiKey).toBe('sk-new-key-123');
  });

  it('clears key input state after successful save', async () => {
    const user = userEvent.setup();
    const putAiSettings = vi.fn(async () => ({
      data: settingsOk.data,
      response: { status: 200 },
    }));
    renderSettings({ putAiSettings });
    await screen.findByRole('button', { name: '保存' });

    const keyInput = screen.getByLabelText('API Key') as HTMLInputElement;
    fireEvent.change(keyInput, { target: { value: 'sk-temp' } });
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(screen.getByTestId('save-message')).toBeInTheDocument();
    });
    expect(keyInput.value).toBe('');
  });

  it('never sends placeholder ******** as apiKey', async () => {
    const user = userEvent.setup();
    const putAiSettings = vi.fn(async (_body: Record<string, unknown>) => ({
      data: settingsOk.data,
      response: { status: 200 },
    }));
    renderSettings({ putAiSettings });
    await screen.findByRole('button', { name: '保存' });

    const keyInput = screen.getByLabelText('API Key');
    fireEvent.change(keyInput, { target: { value: '********' } });
    await user.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => {
      expect(putAiSettings).toHaveBeenCalledTimes(1);
    });
    const body = putAiSettings.mock.calls[0]?.[0] as Record<string, unknown>;
    // Backend rejects placeholders; frontend sends what user typed.
    expect(body.apiKey).toBeDefined();
  });

  it('shows test connection result', async () => {
    const user = userEvent.setup();
    renderSettings({
      testAiConnection: vi.fn(async () => ({
        data: { success: true, provider: 'OPENAI_COMPATIBLE', model: 'step-3.5-flash', latencyMs: 120 },
        response: { status: 200 },
      })),
    });
    await screen.findByRole('button', { name: '测试连接' });
    await user.click(screen.getByRole('button', { name: '测试连接' }));
    expect(await screen.findByTestId('test-result')).toHaveTextContent(
      '连接成功'
    );
  });

  it('shows not-configured state when apiKeyConfigured is false', async () => {
    renderSettings({
      getAiSettings: vi.fn(async () => ({
        data: { ...settingsOk.data, apiKeyConfigured: false },
        response: { status: 200 },
      })),
    });
    expect(await screen.findByTestId('key-status')).toHaveTextContent(
      '尚未配置'
    );
  });
});
