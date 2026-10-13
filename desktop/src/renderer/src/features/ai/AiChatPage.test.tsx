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

  it('renders no composer or conversation controls while unconfigured (W10)', async () => {
    renderChat();
    await screen.findByTestId('provider-not-ready');
    // A warning beside a live "新对话"/send control is not a gate: the user
    // can still submit a message that the provider cannot answer.
    expect(
      screen.queryByRole('button', { name: '新对话' })
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText('消息')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '发送' })).not.toBeInTheDocument();
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

    const textarea = await screen.findByLabelText('消息');
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

describe('AI Chat — paged history (W11)', () => {
  const READY = vi.fn(async () => ({
    data: { enabled: true, apiKeyConfigured: true },
    response: { status: 200 },
  }));

  it('keeps older messages reachable page by page instead of treating page 0 as the whole history', async () => {
    const user = userEvent.setup();
    const listMessages = vi.fn(
      async (_spaceId: number, _convId: number, query?: { page?: number }) => {
        const page = query?.page ?? 0;
        return {
          data: {
            content: [
              {
                id: page * 10 + 1,
                role: 'USER',
                content: page === 0 ? 'PAGE-0' : 'PAGE-1',
              },
            ],
            page,
            size: 1,
            totalElements: 2,
            totalPages: 2,
          },
          response: { status: 200 },
        };
      }
    );

    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: READY,
        listConversations: vi.fn(async () => ({
          data: {
            content: [{ id: 1, title: '长对话', status: 'ACTIVE' }],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
        listMessages,
      }),
      initialEntries: ['/spaces/7/ai/1'],
      routePath: '/spaces/:spaceId/ai/:conversationId',
    });

    expect(await screen.findByText('PAGE-0')).toBeInTheDocument();
    expect(screen.queryByText('PAGE-1')).not.toBeInTheDocument();
    // The server says two messages exist, so the UI must offer the rest
    // rather than silently showing half a conversation.
    expect(await screen.findByTestId('ai-message-count')).toHaveTextContent(
      '已加载 1 / 2'
    );

    await user.click(screen.getByRole('button', { name: '加载更多历史消息' }));

    expect(await screen.findByText('PAGE-1')).toBeInTheDocument();
    expect(listMessages).toHaveBeenCalledWith(7, 1, { page: 1, size: 50 });
    expect(screen.queryByTestId('ai-message-count')).not.toBeInTheDocument();
  });

  it('loads further conversations when the list reports more items', async () => {
    const user = userEvent.setup();
    const listConversations = vi.fn(
      async (_spaceId: number, query?: { page?: number }) => {
        const page = query?.page ?? 0;
        return {
          data: {
            content: [
              {
                id: page * 10 + 1,
                title: page === 0 ? '对话A' : '对话B',
                status: 'ACTIVE',
              },
            ],
            page,
            size: 1,
            totalElements: 2,
            totalPages: 2,
          },
          response: { status: 200 },
        };
      }
    );

    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: READY,
        listConversations,
        listMessages: vi.fn(async () => ({
          data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/ai'],
      routePath: '/spaces/:spaceId/ai',
    });

    expect(await screen.findByRole('link', { name: '对话A' })).toBeInTheDocument();
    expect(
      await screen.findByTestId('ai-conversation-count')
    ).toHaveTextContent('已显示 1 / 2');

    await user.click(screen.getByRole('button', { name: '加载更多对话' }));

    expect(await screen.findByRole('link', { name: '对话B' })).toBeInTheDocument();
    expect(listConversations).toHaveBeenCalledWith(7, { page: 1, size: 20 });
  });
});
