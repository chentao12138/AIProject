/**
 * AI Chat provider-not-configured + identity boundary tests
 * (FE-AI-001-PRE-COMMIT-FINALIZE-01), plus the A3.2 (G07) context attachment
 * and A3.3 (G08) citation-routing behaviour.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
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

/* ─────────────── A3.2 (G07): attached message context ─────────────── */

/* A fresh mock per render: nothing in this repo clears mocks between tests, so
   a shared one would let an earlier test's call satisfy a later assertion. */
const settingsReady = () =>
  vi.fn(async () => ({
    data: { enabled: true, apiKeyConfigured: true },
    response: { status: 200 },
  }));

const conversations = () =>
  vi.fn(async () => ({
    data: {
      content: [{ id: 1, title: '对话一', status: 'ACTIVE' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    },
    response: { status: 200 },
  }));

const noMessages = () =>
  vi.fn(async () => ({
    data: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 },
    response: { status: 200 },
  }));

const sent = () =>
  vi.fn(async () => ({
    data: {
      userMessage: { id: 11, role: 'USER', content: 'OK' },
      assistantMessage: { id: 12, role: 'ASSISTANT', content: 'REPLY' },
    },
    response: { status: 201 },
  }));

/** Open a conversation with `query` as the AI route's own query string. */
function renderAttached(query = '') {
  const sendMessage = sent();
  renderWithProviders(<AiChatPage />, {
    apiClient: mockApiClient({
      getAiSettings: settingsReady(),
      listConversations: conversations(),
      listMessages: noMessages(),
      sendMessage,
    }),
    initialEntries: [`/spaces/7/ai/1${query}`],
    routePath: '/spaces/:spaceId/ai/:conversationId',
  });
  return { sendMessage };
}

describe('AI Chat — attached message context (A3.2 / G07)', () => {
  it('sends exactly the context id the learner arrived with, and says so first', async () => {
    const user = userEvent.setup();
    const { sendMessage } = renderAttached('?attachContentBlockId=31');

    await screen.findByLabelText('消息');
    // Visible BEFORE sending: the composer names the attached material, so the
    // claim and the request body cannot drift apart.
    expect(screen.getByTestId('ai-attach-summary')).toHaveTextContent(
      '本条提问附带：正文片段 #31'
    );

    await user.type(screen.getByLabelText('消息'), '这段在讲什么');
    await user.click(screen.getByRole('button', { name: '发送' }));

    // Deep equality: a body carrying any other context id fails this assert.
    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, {
        content: '这段在讲什么',
        contentBlockId: 31,
      })
    );
  });

  it('sends content alone when nothing is attached, and says that too', async () => {
    const user = userEvent.setup();
    const { sendMessage } = renderAttached();

    await screen.findByLabelText('消息');
    expect(screen.getByTestId('ai-attach-none')).toHaveTextContent(
      '不附带任何材料'
    );
    expect(screen.queryByTestId('ai-attach-summary')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('消息'), '随便问问');
    await user.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, { content: '随便问问' })
    );
  });

  it('keeps the narrowest pointer when a whole chain arrives in the URL', async () => {
    // The backend derives a block's page and source (and rejects a source/page
    // pair that disagrees), so the body must not carry the trio.
    const user = userEvent.setup();
    const { sendMessage } = renderAttached(
      '?attachSourceId=5&attachSourcePageId=20&attachContentBlockId=31'
    );

    await screen.findByLabelText('消息');
    expect(screen.getByTestId('ai-attach-summary')).toHaveTextContent(
      '正文片段 #31'
    );
    expect(screen.getByTestId('ai-attach-summary')).not.toHaveTextContent(
      '学习来源 #5'
    );

    await user.type(screen.getByLabelText('消息'), '这里呢');
    await user.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, {
        content: '这里呢',
        contentBlockId: 31,
      })
    );
  });

  it('drops the attachment when the learner says so, without touching the draft', async () => {
    const user = userEvent.setup();
    const { sendMessage } = renderAttached('?attachSourceId=5');

    await screen.findByLabelText('消息');
    await user.type(screen.getByLabelText('消息'), '先不针对这条');
    await user.click(screen.getByRole('button', { name: '不附带' }));

    expect(screen.getByTestId('ai-attach-none')).toBeInTheDocument();
    expect(screen.getByLabelText('消息')).toHaveValue('先不针对这条');

    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, {
        content: '先不针对这条',
      })
    );
  });

  it('refuses an unusable id out loud instead of sending it', async () => {
    const user = userEvent.setup();
    const { sendMessage } = renderAttached('?attachSourceId=abc');

    await screen.findByLabelText('消息');
    expect(screen.getByTestId('ai-attach-rejected')).toHaveTextContent(
      'attachSourceId'
    );
    expect(screen.getByTestId('ai-attach-rejected')).toHaveTextContent(
      '不会带上它'
    );
    expect(screen.getByTestId('ai-attach-none')).toBeInTheDocument();

    await user.type(screen.getByLabelText('消息'), '问题');
    await user.click(screen.getByRole('button', { name: '发送' }));
    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, { content: '问题' })
    );
  });

  it('carries the attachment through opening a conversation from the list', async () => {
    const user = userEvent.setup();
    const sendMessage = sent();
    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: settingsReady(),
        listConversations: conversations(),
        listMessages: noMessages(),
        sendMessage,
      }),
      initialEntries: ['/spaces/7/ai?attachKnowledgePointId=10'],
      // Both AI routes on one matcher, so the click really navigates inside
      // this render instead of falling out of the rendered subtree.
      routePath: '/spaces/:spaceId/ai/:conversationId?',
    });

    // Stated while no conversation is open yet, so the learner knows the
    // attachment is waiting rather than lost.
    expect(await screen.findByTestId('ai-attach-summary')).toHaveTextContent(
      '知识点 #10'
    );
    const conversationLink = await screen.findByRole('link', {
      name: '对话一',
    });
    // The route into the chat must not drop the query — that is where the
    // attachment lives.
    expect(conversationLink).toHaveAttribute(
      'href',
      '/spaces/7/ai/1?attachKnowledgePointId=10'
    );

    await user.click(conversationLink);
    await user.type(await screen.findByLabelText('消息'), '这个点没懂');
    await user.click(screen.getByRole('button', { name: '发送' }));

    await waitFor(() =>
      expect(sendMessage).toHaveBeenCalledWith(7, 1, {
        content: '这个点没懂',
        knowledgePointId: 10,
      })
    );
  });
  it('surfaces the server refusing an id that is not in this space, and keeps no phantom message', async () => {
    // The backend resolves every attached id against the LearningSpace
    // (AiLearningContextService.resolveSelectedContext) and answers 400
    // VALIDATION_ERROR for an unknown / foreign-space / soft-deleted one, so an
    // attachment the server refuses must fail visibly — not sit in the thread
    // as if it had been asked.
    const user = userEvent.setup();
    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: settingsReady(),
        listConversations: conversations(),
        listMessages: noMessages(),
        sendMessage: vi.fn(async () => ({
          data: undefined,
          error: {
            code: 'VALIDATION_ERROR',
            detail: 'sourceId does not reference an entity in this learning space',
          },
          response: { status: 400 },
        })),
      }),
      initialEntries: ['/spaces/7/ai/1?attachSourceId=999999'],
      routePath: '/spaces/:spaceId/ai/:conversationId',
    });

    await user.type(await screen.findByLabelText('消息'), '这条不属于这里');
    await user.click(screen.getByRole('button', { name: '发送' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('校验未通过');
    expect(alert).toHaveTextContent(
      'sourceId does not reference an entity in this learning space'
    );
    // Rolled back: the optimistically inserted question is not left behind.
    expect(screen.queryByText('这条不属于这里')).not.toBeInTheDocument();
  });
});

/* ─────────────── A3.3 (G08): citation routing ─────────────── */

describe('AI Chat — citation deep links (A3.3 / G08)', () => {
  function renderRefs(references: unknown[]) {
    renderWithProviders(<AiChatPage />, {
      apiClient: mockApiClient({
        getAiSettings: settingsReady(),
        listConversations: conversations(),
        listMessages: vi.fn(async () => ({
          data: {
            content: [
              {
                id: 102,
                role: 'ASSISTANT',
                content: '答案',
                references,
              },
            ],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/ai/1'],
      routePath: '/spaces/:spaceId/ai/:conversationId',
    });
    return screen.findByTestId('ai-refs');
  }

  it('deep-links each structured citation to the route its type owns', async () => {
    await renderRefs([
      { type: 'SOURCE', id: 5, sourceId: 5, title: '讲义' },
      { type: 'SOURCE_PAGE', id: 20, sourceId: 5, sourcePageId: 20, title: '第 3 页' },
      {
        type: 'CONTENT_BLOCK',
        id: 31,
        sourceId: 5,
        sourcePageId: 20,
        contentBlockId: 31,
        title: '片段',
      },
      { type: 'KNOWLEDGE_POINT', id: 10, title: '贝叶斯' },
      { type: 'QUESTION', id: 12, title: '练习' },
      { type: 'NOTE', id: 15, title: '笔记' },
    ]);

    expect(screen.getByRole('link', { name: '讲义' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5'
    );
    expect(screen.getByRole('link', { name: '第 3 页' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5?page=20'
    );
    expect(screen.getByRole('link', { name: '片段' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5?page=20&block=31'
    );
    expect(screen.getByRole('link', { name: '贝叶斯' })).toHaveAttribute(
      'href',
      '/spaces/7/knowledge/10'
    );
    expect(screen.getByRole('link', { name: '练习' })).toHaveAttribute(
      'href',
      '/spaces/7/questions/12'
    );
    expect(screen.getByRole('link', { name: '笔记' })).toHaveAttribute(
      'href',
      '/spaces/7/notes/15'
    );
  });

  it('renders a citation with no usable id as text, never as a plausible link', async () => {
    const refs = await renderRefs([
      // `id` here is the wrong_question ROW and no question id travels with it.
      { type: 'WRONG_QUESTION', id: 44, title: '错题引用' },
      // Pointer columns are ON DELETE SET NULL (V065): the block is gone.
      { type: 'CONTENT_BLOCK', id: 31, title: '删掉的片段' },
      // A type this build has never seen.
      { type: 'FLASHCARD', id: 9, sourceId: 5, title: '新类型' },
    ]);
    const area = within(refs);

    for (const title of ['错题引用', '删掉的片段', '新类型']) {
      expect(area.getByText(title)).toBeInTheDocument();
      expect(area.queryByRole('link')).not.toBeInTheDocument();
    }
    // Nothing invented: no `/questions/…`, no `/sources/…`, no `/sources/undefined`.
    expect(
      refs.querySelectorAll<HTMLAnchorElement>('a[href]').length
    ).toBe(0);
  });

  it('shows locator as a label and never routes on it', async () => {
    const refs = await renderRefs([
      // locator "P12" is a DOCUMENT page number; the page ROW id is 20.
      {
        type: 'CONTENT_BLOCK',
        id: 31,
        sourceId: 5,
        sourcePageId: 20,
        contentBlockId: 31,
        locator: 'P12',
        title: '带定位的片段',
      },
    ]);

    // The label is shown (text is split across nodes by the interpolation, so
    // this asserts the rendered text of the whole citation block).
    expect(refs.textContent).toContain('P12');
    expect(within(refs).getByRole('link', { name: '带定位的片段' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5?page=20&block=31'
    );
    // The locator's number never becomes a route parameter.
    expect(refs.querySelector('a[href*="12"]')).toBeNull();
  });
});
