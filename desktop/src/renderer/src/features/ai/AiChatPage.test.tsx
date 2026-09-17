/**
 * AI Chat provider-not-configured + identity boundary tests
 * (FE-AI-001-PRE-COMMIT-FINALIZE-01).
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AiChatPage } from './AiChatPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

function renderChat(overrides: Record<string, unknown> = {}) {
  return renderWithProviders(<AiChatPage />, {
    apiClient: mockApiClient({
      getAiSettings: vi.fn(async () => ({
        data: {
          enabled: false,
          apiKeyConfigured: false,
          provider: 'OPENAI_COMPATIBLE',
          preset: 'STEPFUN',
        },
        response: { status: 200 },
      })),
      listConversations: vi.fn(async () => ({
        data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        response: { status: 200 },
      })),
      ...overrides,
    }),
    initialEntries: ['/spaces/7/ai'],
    routePath: '/spaces/:spaceId/ai',
  });
}

describe('AI Chat — provider-not-configured flow', () => {
  it('shows warning and settings link when provider not configured', async () => {
    renderChat();
    expect(await screen.findByTestId('provider-not-ready')).toHaveTextContent(
      '尚未配置'
    );
    expect(
      screen.getByRole('link', { name: /AI Settings/ })
    ).toHaveAttribute('href', '/settings/ai');
  });

  it('does not show warning when provider is configured and enabled', async () => {
    renderChat({
      getAiSettings: vi.fn(async () => ({
        data: {
          enabled: true,
          apiKeyConfigured: true,
          provider: 'OPENAI_COMPATIBLE',
          preset: 'STEPFUN',
        },
        response: { status: 200 },
      })),
    });
    // Wait for settings to load, then confirm no warning.
    await screen.findByRole('button', { name: '新对话' });
    expect(screen.queryByTestId('provider-not-ready')).not.toBeInTheDocument();
  });
});

describe('AI Chat — invalid conversation id', () => {
  it('shows placeholder when no conversation selected', async () => {
    renderChat({
      getAiSettings: vi.fn(async () => ({
        data: { enabled: true, apiKeyConfigured: true },
        response: { status: 200 },
      })),
    });
    expect(await screen.findByText('选择或创建对话')).toBeInTheDocument();
  });
});

describe('AI Chat — conversation switching', () => {
  it('does not leak the previous conversation messages into another conversation', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: vi.fn(async () => ({
          data: { enabled: true, apiKeyConfigured: true },
          response: { status: 200 },
        })),
        listConversations: vi.fn(async () => ({
          data: {
            content: [
              { id: 1, title: '对话一', status: 'ACTIVE' },
              { id: 2, title: '对话二', status: 'ACTIVE' },
            ],
            page: 0,
            size: 20,
            totalElements: 2,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
        listMessages: vi.fn(async () => ({
          data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
          response: { status: 200 },
        })),
        sendMessage: vi.fn(async () => ({
          data: {
            userMessage: { id: 11, role: 'USER', content: 'MSG-CONV-ONE' },
            assistantMessage: { id: 12, role: 'ASSISTANT', content: 'REPLY-CONV-ONE' },
          },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/ai/1'],
      routePath: '/spaces/:spaceId/ai/:conversationId',
    });

    const textarea = await screen.findByLabelText('Message');
    await user.type(textarea, 'MSG-CONV-ONE');
    await user.click(screen.getByRole('button', { name: '发送' }));
    expect(await screen.findByText('REPLY-CONV-ONE')).toBeInTheDocument();

    await user.click(await screen.findByRole('link', { name: '对话二' }));
    await waitFor(() => {
      expect(screen.queryByText('REPLY-CONV-ONE')).not.toBeInTheDocument();
    });
    expect(screen.queryByText('MSG-CONV-ONE')).not.toBeInTheDocument();
    expect(screen.getByText('发送第一条消息开始对话。')).toBeInTheDocument();
  });
});

describe('AI Chat — message history persistence', () => {
  it('loads existing messages from server when opening a conversation', async () => {
    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: vi.fn(async () => ({
          data: { enabled: true, apiKeyConfigured: true },
          response: { status: 200 },
        })),
        listConversations: vi.fn(async () => ({
          data: {
            content: [{ id: 1, title: '历史对话', status: 'ACTIVE' }],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
        listMessages: vi.fn(async () => ({
          data: {
            content: [
              { id: 101, role: 'USER', content: '旧问题', createdAt: '2024-01-01T00:00:00Z' },
              { id: 102, role: 'ASSISTANT', content: '旧回答', createdAt: '2024-01-01T00:00:01Z' },
            ],
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/ai/1'],
      routePath: '/spaces/:spaceId/ai/:conversationId',
    });

    expect(await screen.findByText('旧问题')).toBeInTheDocument();
    expect(await screen.findByText('旧回答')).toBeInTheDocument();
    expect(screen.getByTestId('ai-messages').querySelectorAll('.ai-message')).toHaveLength(2);
  });
});
