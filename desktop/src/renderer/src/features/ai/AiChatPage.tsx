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
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { entityQuery, spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import { formatDateTime } from '../../lib/format';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import type { components } from '@aistudy/api-client';

/** Cache shape of one message page (the only source for the chat list). */
type MessagePageResponse = components['schemas']['MessagePageResponse'];

export function AiChatPage() {
  const api = useApiClient();
  const spaceId = useSpaceId();
  const { conversationId: convIdParam } = useParams();
  const conversationId = parsePositiveIdParam(convIdParam);

  const settingsQuery = useQuery({
    queryKey: queryKeys.aiSettings,
    queryFn: () => api.getAiSettings().then(unwrap),
  });

  if (spaceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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

  const listQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.aiConversations(spaceId),
      (id) => api.listConversations(id)
    )
  );

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

  const messagesKey = queryKeys.aiMessages(spaceId, conversationId);

  const messagesQuery = useQuery(
    entityQuery(spaceId, conversationId, messagesKey, (id, convId) =>
      api.listMessages(id, convId)
    )
  );
  const messages = (messagesQuery.data?.content ?? []) as MessageItem[];

  const send = useMutation({
    mutationFn: (content: string) =>
      api.sendMessage(spaceId, conversationId, { content }).then(unwrap),
    onMutate: async (content: string) => {
      setSendError(null);
      const previous =
        queryClient.getQueryData<MessagePageResponse>(messagesKey);
      queryClient.setQueryData<MessagePageResponse>(messagesKey, (current) => ({
        ...current,
        content: [
          ...(current?.content ?? []),
          {
            id: Date.now(),
            role: 'USER',
            content,
            createdAt: new Date().toISOString(),
          },
        ],
      }));
      setPrompt('');
      return { previous };
    },
    onSuccess: (data) => {
      const { userMessage, assistantMessage } = data ?? {};
      if (userMessage && assistantMessage) {
        // The response carries the persisted pair, so writing it into the
        // cache IS the resync — a refetch here would race this update.
        queryClient.setQueryData<MessagePageResponse>(
          messagesKey,
          (current) => ({
            ...current,
            content: [
              ...(current?.content ?? []).slice(0, -1),
              userMessage,
              assistantMessage,
            ],
          })
        );
      } else if (assistantMessage) {
        queryClient.setQueryData<MessagePageResponse>(
          messagesKey,
          (current) => ({
            ...current,
            content: [...(current?.content ?? []), assistantMessage],
          })
        );
      } else {
        // Nothing persisted came back: resync instead of keeping a locally
        // invented message in the list.
        void queryClient.invalidateQueries({ queryKey: messagesKey });
      }
      void queryClient.invalidateQueries({
        queryKey: queryKeys.aiConversations(spaceId),
      });
    },
    onError: (error, _content, context) => {
      if (context?.previous) {
        queryClient.setQueryData<MessagePageResponse>(
          messagesKey,
          context.previous
        );
      } else {
        void queryClient.invalidateQueries({ queryKey: messagesKey });
      }
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
        {messagesQuery.isPending && messages.length === 0 && !busy && (
          <LoadingState text="加载消息…" />
        )}
        {messagesQuery.isError && (
          <ErrorState
            message={normalizeApiError(messagesQuery.error).message}
            onRetry={() => void messagesQuery.refetch()}
          />
        )}
        {messages.length === 0 && !messagesQuery.isPending && !messagesQuery.isError && !busy && (
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
                  {msg.references.map((ref, ri) => {
                    const refId = ref.id;
                    let href: string | undefined;
                    if (ref.type === 'SOURCE' && isPositiveId(refId)) {
                      href = `/spaces/${spaceId}/sources/${refId}`;
                    } else if (
                      ref.type === 'KNOWLEDGE_POINT' &&
                      isPositiveId(refId)
                    ) {
                      href = `/spaces/${spaceId}/knowledge/${refId}`;
                    } else if (
                      ref.type === 'QUESTION' &&
                      isPositiveId(refId)
                    ) {
                      href = `/spaces/${spaceId}/questions/${refId}`;
                    }
                    return (
                      <li key={ri}>
                        {href ? (
                          <Link to={href} className="nav-link">
                            {ref.title ?? `#${refId}`}
                          </Link>
                        ) : (
                          <>
                            {ref.title ?? `#${refId ?? '?'}`}
                          </>
                        )}
                        {ref.snippet ? ` — ${ref.snippet.slice(0, 80)}` : ''}
                      </li>
                    );
                  })}
                </ul>
              </div>
            )}
          </article>
        ))}

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
