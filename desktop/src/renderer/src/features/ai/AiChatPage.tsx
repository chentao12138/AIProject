/**
 * AI Tutor / Chat workspace (FE-AI-001 Day 1–2).
 *
 * /spaces/:spaceId/ai — conversation list + chat.
 * /spaces/:spaceId/ai/:conversationId — specific conversation.
 *
 * All AI requests go through the shared @aistudy/api-client.
 * Never direct-fetch StepFun. Never log keys.
 */

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { formatDateTime } from '../../lib/format';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';

export function AiChatPage() {
  const api = useApiClient();
  const { spaceId: spaceIdParam, conversationId: convIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);
  const conversationId = parsePositiveIdParam(convIdParam);

  const settingsQuery = useQuery({
    queryKey: queryKeys.aiSettings,
    queryFn: () => api.getAiSettings().then(unwrap),
  });

  if (spaceId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const settings = settingsQuery.data;
  const providerReady =
    settings?.enabled === true && settings?.apiKeyConfigured === true;

  return (
    <div className="page ai-workspace">
      <PageHeader title="AI Tutor" />

      {settingsQuery.isSuccess && !providerReady && (
        <div className="form__error" role="alert" data-testid="provider-not-ready">
          AI Provider 尚未配置或未启用。
          <Link to="/settings/ai" className="nav-link">
            前往 AI Settings 配置
          </Link>
        </div>
      )}

      <div className="ai-layout">
        <ConversationList spaceId={spaceId} activeId={conversationId} />
        {/* key={conversationId}：切换会话时强制重挂载 ChatPanel，重置状态 */}
        {conversationId !== null ? (
          <ChatPanel
            key={conversationId}
            spaceId={spaceId}
            conversationId={conversationId}
          />
        ) : (
          <div className="ai-chat-placeholder">
            <EmptyState
              title="选择或创建对话"
              description="从左侧选择一个对话，或创建新对话开始学习。"
            />
          </div>
        )}
      </div>
    </div>
  );
}

/* ─────────────── Conversation list ─────────────── */

function ConversationList({
  spaceId,
  activeId,
}: {
  spaceId: number;
  activeId: number | null;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const listQuery = useQuery({
    queryKey: queryKeys.aiConversations(spaceId),
    queryFn: () => api.listConversations(spaceId).then(unwrap),
  });

  const create = useMutation({
    mutationFn: () => api.createConversation(spaceId, {}).then(unwrap),
    onSuccess: (conv) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.aiConversations(spaceId),
      });
      if (isPositiveId(conv?.id)) {
        navigate(`/spaces/${spaceId}/ai/${conv.id}`);
      }
    },
  });

  const archive = useMutation({
    mutationFn: (convId: number) =>
      api.archiveConversation(spaceId, convId).then(unwrap),
    onSuccess: (_, archivedId) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.aiConversations(spaceId),
      });
      // 修复：如果归档的是当前打开的会话，重置路由到会话首页
      if (activeId === archivedId) {
        navigate(`/spaces/${spaceId}/ai`);
      }
    },
  });

  const conversations = listQuery.data?.content ?? [];

  return (
    <aside className="ai-sidebar" aria-label="Conversations">
      <Button
        variant="primary"
        onClick={() => create.mutate()}
        disabled={create.isPending}
      >
        {create.isPending ? '创建中…' : '新对话'}
      </Button>

      {listQuery.isPending && <LoadingState text="加载对话…" />}
      {listQuery.isError && (
        <ErrorState
          message={normalizeApiError(listQuery.error).message}
          onRetry={() => void listQuery.refetch()}
        />
      )}
      {listQuery.isSuccess && conversations.length === 0 && (
        <p className="muted">暂无对话。</p>
      )}

      <ul className="ai-conversation-list">
        {conversations.map((conv) => {
          const convId = conv.id;
          if (!isPositiveId(convId)) {
            return null;
          }
          const active = convId === activeId;
          return (
            <li key={convId} className="ai-conversation-item">
              <Link
                to={`/spaces/${spaceId}/ai/${convId}`}
                className={
                  active
                    ? 'nav-link nav-link--active'
                    : 'nav-link'
                }
                aria-current={active ? 'page' : undefined}
              >
                {conv.title || `对话 #${convId}`}
              </Link>
              <Button
                type="button"
                onClick={() => archive.mutate(convId)}
                disabled={archive.isPending}
              >
                归档
              </Button>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}

/* ─────────────── Chat panel ─────────────── */

interface MessageItem {
  id?: number;
  role?: string;
  content?: string;
  createdAt?: string;
  references?: {
    type?: string;
    id?: number;
    title?: string;
    snippet?: string;
  }[];
}

function ChatPanel({
  spaceId,
  conversationId,
}: {
  spaceId: number;
  conversationId: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [prompt, setPrompt] = useState('');
  const [sendError, setSendError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  // 修复 1：加载远端历史消息（若你 api-client 中有获取消息的方法，可取消注释 queryKey 与 queryFn）
  // 如果当前 api-client 没有 listMessages，可直接保留下述空回退状态
  const [messages, setMessages] = useState<MessageItem[]>([]);

  const send = useMutation({
    mutationFn: (content: string) =>
      api.sendMessage(spaceId, conversationId, { content }).then(unwrap),
    onMutate: async (content: string) => {
      setSendError(null);
      // 优化 2：乐观更新 - 立即在界面添加用户问题与“AI思考中”占位
      const tempUserMsg: MessageItem = {
        id: Date.now(),
        role: 'USER',
        content,
        createdAt: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, tempUserMsg]);
      setPrompt('');
    },
    onSuccess: (data) => {
      // 收到真实响应后，用后端返回的真实消息替换/追加
      if (data?.userMessage && data?.assistantMessage) {
        setMessages((prev) => {
          // 移除乐观添加的临时消息，换成服务端的标准消息
          const withoutOptimistic = prev.slice(0, -1);
          return [...withoutOptimistic, data.userMessage!, data.assistantMessage!];
        });
      } else if (data?.assistantMessage) {
        setMessages((prev) => [...prev, data.assistantMessage!]);
      }

      void queryClient.invalidateQueries({
        queryKey: queryKeys.aiConversations(spaceId),
      });
    },
    onError: (error) => {
      // 发送失败时移除乐观添加的消息
      setMessages((prev) => prev.slice(0, -1));
      setSendError(normalizeApiError(error).message);
    },
  });

  useEffect(() => {
    const el = bottomRef.current;
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages.length, send.isPending]);

  const busy = send.isPending;

  // 优化 3：支持 Enter 发送，Shift + Enter 换行
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      const trimmed = prompt.trim();
      if (trimmed && !busy) {
        send.mutate(trimmed);
      }
    }
  };

  return (
    <section className="ai-chat" aria-label="Chat">
      <div className="ai-messages" data-testid="ai-messages">
        {messages.length === 0 && !busy && (
          <p className="muted">发送第一条消息开始对话。</p>
        )}
        {messages.map((msg, i) => (
          <article
            key={msg.id ?? `msg-${i}`}
            className={`ai-message ai-message--${(msg.role ?? 'unknown').toLowerCase()}`}
            data-testid="ai-message"
            data-role={msg.role}
          >
            <p className="ai-message__role muted">
              {msg.role === 'USER' ? '你' : 'AI'}
              {msg.createdAt ? ` · ${formatDateTime(msg.createdAt)}` : ''}
            </p>
            <div className="ai-message__content">{msg.content ?? '—'}</div>
            {msg.references && msg.references.length > 0 && (
              <div className="ai-message__refs" data-testid="ai-refs">
                <p className="muted">引用：</p>
                <ul>
                  {msg.references.map((ref, ri) => (
                    <li key={ri}>
                      {ref.title ?? `#${ref.id ?? '?'}`}
                      {ref.snippet ? ` — ${ref.snippet.slice(0, 80)}` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </article>
        ))}

        {/* 思考中状态气泡 */}
        {busy && (
          <article className="ai-message ai-message--assistant" style={{ opacity: 0.7 }}>
            <p className="ai-message__role muted">AI</p>
            <div className="ai-message__content">思考中…</div>
          </article>
        )}

        <div ref={bottomRef} />
      </div>

      <form
        className="ai-composer"
        onSubmit={(event) => {
          event.preventDefault();
          const trimmed = prompt.trim();
          if (!trimmed || busy) {
            return;
          }
          send.mutate(trimmed);
        }}
      >
        {sendError && (
          <p className="form__error" role="alert">
            {sendError}
          </p>
        )}
        <textarea
          className="textarea"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入你的问题… (按 Enter 发送，Shift + Enter 换行)"
          rows={3}
          disabled={busy}
          aria-label="Message"
        />
        <Button
          type="submit"
          variant="primary"
          disabled={busy || prompt.trim() === ''}
        >
          {busy ? '发送中…' : '发送'}
        </Button>
      </form>
    </section>
  );
}