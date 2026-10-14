/**
 * AI Tutor / Chat workspace (FE-AI-001 Day 1–2).
 *
 * /spaces/:spaceId/ai — conversation list + chat.
 * /spaces/:spaceId/ai/:conversationId — specific conversation.
 *
 * All AI requests go through the shared @aistudy/api-client.
 * Never direct-fetch StepFun. Never log keys.
 *
 * A3.2 (G07): another module can open this route with the material the learner
 * is looking at — `?attachSourceId=` / `?attachSourcePageId=` /
 * `?attachContentBlockId=` / `?attachKnowledgePointId=` (see
 * `aiContext.ts:AI_ATTACH_PARAM_KEYS` for why these are NOT the Notes prefill
 * keys). What is attached is listed above the composer before a single word is
 * sent, the list is exactly what `SendMessageRequest` carries, and an
 * unusable id in the URL is named as refused rather than dropped quietly.
 * A3.3 (G08): citations route on the structured `ContextReference` ids, never
 * on `locator`.
 */

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { useSpaceId } from '../../lib/space-scope';
import { formatDateTime } from '../../lib/format';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import type { AiMessagePage } from '../../lib/types';
import {
  AI_ATTACH_PARAM_KEYS,
  attachmentSearch,
  attachmentSummary,
  citationHref,
  citationLabel,
  hasAttachedContext,
  messageBodyWithAttachment,
  readAiAttachment,
  type AiAttachedContext,
} from './aiContext';

/** Message row as the contract reports it, derived from the paged response. */
type MessageItem = NonNullable<NonNullable<AiMessagePage>['content']>[number];

/** Server-side page sizes; both endpoints paginate (see openapi conversation/message pages). */
const CONVERSATION_PAGE_SIZE = 20;
const MESSAGE_PAGE_SIZE = 50;
/** A server that keeps claiming more items must not spin us in a loop. */
const MAX_PAGES_PER_LOAD = 20;

interface PageLike<T> {
  content?: T[];
  size?: number;
  totalElements?: number;
}

/** What the cache holds for a paged list: the accumulated rows plus the server total. */
interface PagedCache<T> {
  content: T[];
  totalElements: number | null;
}

/**
 * Fetch pages 0..pageCount-1 and concatenate them.
 *
 * The server reports `page`/`size`/`totalElements`, so "how much exists" is
 * always server truth — the UI never guesses a total from what it happened to
 * receive, and it never asks the backend for everything at once.
 */
async function collectPages<T>(
  fetchPage: (page: number) => Promise<PageLike<T> | undefined>,
  pageCount: number
): Promise<PagedCache<T>> {
  const content: T[] = [];
  const seen = new Set<string>();
  let totalElements: number | null = null;

  for (let index = 0; index < Math.min(pageCount, MAX_PAGES_PER_LOAD); index += 1) {
    const data = await fetchPage(index);
    const batch = data?.content ?? [];
    if (typeof data?.totalElements === 'number') {
      totalElements = data.totalElements;
    }
    batch.forEach((item, position) => {
      const id = (item as { id?: number }).id;
      const dedupeKey =
        typeof id === 'number' ? `id:${id}` : `row:${index}:${position}`;
      if (seen.has(dedupeKey)) {
        return;
      }
      seen.add(dedupeKey);
      content.push(item);
    });
    if (batch.length === 0) break;
    // A short page is the last page even when the server omits its total.
    if (typeof data?.size === 'number' && batch.length < data.size) break;
    if (totalElements !== null && content.length >= totalElements) break;
  }

  return { content, totalElements };
}

export function AiChatPage() {
  const api = useApiClient();
  const spaceId = useSpaceId();
  const { conversationId: convIdParam } = useParams();
  const conversationId = parsePositiveIdParam(convIdParam);
  const [searchParams, setSearchParams] = useSearchParams();

  // A3.2: the material this page was opened with. Read from this route's own
  // query string, so a reload states the attachment again instead of relying
  // on memory, and cleared here rather than in the panel — the attachment
  // belongs to the conversation the learner is in, not to one panel instance.
  const attachment = readAiAttachment(searchParams);

  const clearAttachment = () => {
    const next = new URLSearchParams(searchParams);
    for (const key of Object.values(AI_ATTACH_PARAM_KEYS)) {
      next.delete(key);
    }
    setSearchParams(next, { replace: true });
  };

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

  // An unconfigured provider cannot answer, so no workspace is rendered: a
  // warning beside a live composer only lets the user discover the failure
  // after the message is already gone.
  if (settingsQuery.isPending) {
    return (
      <div className="page ai-workspace">
        <PageHeader title="AI 辅导" />
        <LoadingState text="检查 AI 配置…" />
      </div>
    );
  }

  if (settingsQuery.isError) {
    return (
      <div className="page ai-workspace">
        <PageHeader title="AI 辅导" />
        <ErrorState
          message={normalizeApiError(settingsQuery.error).message}
          onRetry={() => void settingsQuery.refetch()}
        />
      </div>
    );
  }

  if (!providerReady) {
    return (
      <div className="page ai-workspace">
        <PageHeader title="AI 辅导" />
        <div
          className="form__error"
          role="alert"
          data-testid="provider-not-ready"
        >
          AI Provider 尚未配置或未启用，无法开始对话。
          <Link to="/settings/ai" className="nav-link">
            前往 AI Settings 配置
          </Link>
        </div>
        <EmptyState
          title="AI 辅导未就绪"
          description="在 AI Settings 中启用 Provider 并保存密钥后，此处会自动恢复对话入口。"
        />
      </div>
    );
  }

  return (
    <div className="page ai-workspace">
      <PageHeader title="AI 辅导" />

      {attachment.rejected.length > 0 && (
        // An unusable id in the URL is told back to the learner instead of
        // being ignored: a link that looked like it carried material must not
        // silently send none.
        <p className="form__error" role="alert" data-testid="ai-attach-rejected">
          链接里的 {attachment.rejected.join('、')} 不是可用的 ID，已忽略：这条提问不会带上它。
        </p>
      )}

      <div className="ai-layout">
        <ConversationList
          spaceId={spaceId}
          activeId={conversationId}
          attachSearch={attachmentSearch(attachment.context)}
        />
        {conversationId !== null ? (
          <ChatPanel
            key={conversationId}
            spaceId={spaceId}
            conversationId={conversationId}
            attachment={attachment.context}
            onClearAttachment={clearAttachment}
          />
        ) : (
          <div className="ai-chat-placeholder">
            <EmptyState
              title="选择或创建对话"
              description="从左侧选择一个对话，或创建新对话开始学习。"
            />
            {hasAttachedContext(attachment.context) && (
              // The attachment survives the switch into a conversation (the
              // list links keep the query), so say it is already pinned before
              // one is opened rather than letting the learner think it was lost.
              <p className="page__note" data-testid="ai-attach-summary">
                将附带到下一条提问：{attachmentSummary(attachment.context).join('、')}。
              </p>
            )}
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
  attachSearch,
}: {
  spaceId: number;
  activeId: number | null;
  /**
   * The attachment query carried over into the conversation
   * (`aiContext.attachmentSearch`), empty when nothing is pinned. A plain
   * absolute link would drop it, and a pinned source that vanished on the way
   * into the chat is exactly the silent no-op this prevents.
   */
  attachSearch: string;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [pagesToLoad, setPagesToLoad] = useState(1);

  const listKey = [
    ...queryKeys.aiConversations(spaceId),
    'pages',
    pagesToLoad,
  ] as const;

  const listQuery = useQuery({
    queryKey: listKey,
    queryFn: () =>
      collectPages(
        (page) =>
          api
            .listConversations(spaceId, {
              page,
              size: CONVERSATION_PAGE_SIZE,
            })
            .then(unwrap),
        pagesToLoad
      ),
  });

  const create = useMutation({
    mutationFn: () => api.createConversation(spaceId, {}).then(unwrap),
    onSuccess: (conv) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.aiConversations(spaceId),
      });
      if (isPositiveId(conv?.id)) {
        navigate(`/spaces/${spaceId}/ai/${conv.id}${attachSearch}`);
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
        navigate(`/spaces/${spaceId}/ai${attachSearch}`);
      }
    },
  });

  const conversations = listQuery.data?.content ?? [];
  const total = listQuery.data?.totalElements ?? null;
  const hasMore = total !== null && conversations.length < total;

  return (
    <aside className="ai-sidebar" aria-label="会话列表">
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
                to={`/spaces/${spaceId}/ai/${convId}${attachSearch}`}
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

      {listQuery.isSuccess && total !== null && (
        <p className="muted" data-testid="ai-conversation-count">
          已显示 {conversations.length} / {total}
        </p>
      )}
      {hasMore && (
        <Button
          type="button"
          variant="secondary"
          onClick={() => setPagesToLoad((count) => count + 1)}
          disabled={listQuery.isFetching}
        >
          加载更多对话
        </Button>
      )}
    </aside>
  );
}

/* ─────────────── Chat panel ─────────────── */

function ChatPanel({
  spaceId,
  conversationId,
  attachment,
  onClearAttachment,
}: {
  spaceId: number;
  conversationId: number;
  /** Context ids the learner pinned; the request body carries exactly these. */
  attachment: AiAttachedContext;
  onClearAttachment: () => void;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [prompt, setPrompt] = useState('');
  const [sendError, setSendError] = useState<string | null>(null);
  const [pagesToLoad, setPagesToLoad] = useState(1);
  const bottomRef = useRef<HTMLDivElement>(null);

  const messagesKey = queryKeys.aiMessages(spaceId, conversationId, pagesToLoad);

  const messagesQuery = useQuery({
    queryKey: messagesKey,
    queryFn: () =>
      collectPages(
        (page) =>
          api
            .listMessages(spaceId, conversationId, {
              page,
              size: MESSAGE_PAGE_SIZE,
            })
            .then(unwrap),
        pagesToLoad
      ),
  });
  const messages = messagesQuery.data?.content ?? [];
  const total = messagesQuery.data?.totalElements ?? null;
  const hasMoreMessages = total !== null && messages.length < total;

  const send = useMutation({
    mutationFn: (content: string) =>
      api
        .sendMessage(
          spaceId,
          conversationId,
          messageBodyWithAttachment(content, attachment)
        )
        .then(unwrap),
    onMutate: async (content: string) => {
      setSendError(null);
      const previous =
        queryClient.getQueryData<PagedCache<MessageItem>>(messagesKey);
      queryClient.setQueryData<PagedCache<MessageItem>>(messagesKey, (current) => ({
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
        totalElements: current?.totalElements ?? null,
      }));
      setPrompt('');
      return { previous };
    },
    onSuccess: (data) => {
      const { userMessage, assistantMessage } = data ?? {};
      if (userMessage && assistantMessage) {
        // The response carries the persisted pair, so writing it into the
        // cache IS the resync — a refetch here would race this update.
        queryClient.setQueryData<PagedCache<MessageItem>>(
          messagesKey,
          (current) => ({
            ...current,
            content: [
              ...(current?.content ?? []).slice(0, -1),
              userMessage,
              assistantMessage,
            ],
            totalElements: current?.totalElements ?? null,
          })
        );
      } else if (assistantMessage) {
        queryClient.setQueryData<PagedCache<MessageItem>>(
          messagesKey,
          (current) => ({
            ...current,
            content: [...(current?.content ?? []), assistantMessage],
            totalElements: current?.totalElements ?? null,
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
        queryClient.setQueryData<PagedCache<MessageItem>>(
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
  const attachedLabels = attachmentSummary(attachment);

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
    <section className="ai-chat" aria-label="对话">
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
        {hasMoreMessages && (
          <div className="ai-history-load">
            {/* Deliberately not "更早的": whether page 0 is the oldest or the
                newest batch is the server's ordering decision, and the contract
                does not state it. Ask for the rest of the history by count
                until that is pinned in OpenAPI. */}
            <Button
              type="button"
              variant="secondary"
              onClick={() => setPagesToLoad((count) => count + 1)}
              disabled={messagesQuery.isFetching}
            >
              加载更多历史消息
            </Button>
            <p className="muted" data-testid="ai-message-count">
              已加载 {messages.length} / {total}
            </p>
          </div>
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
                    // A3.3 / G08: the destination comes from the structured ids
                    // of this citation's type (`aiContext.citationHref`), never
                    // from `locator`, which is a printed label ("P12") whose
                    // number is a document page number rather than the page-row
                    // id the reader selects on.
                    const href = citationHref(spaceId, ref);
                    const typeLabel = citationLabel(ref.type);
                    const title = (ref.title ?? '').trim();
                    const label =
                      title !== '' ? title : `${typeLabel} #${ref.id ?? '?'}`;
                    return (
                      <li key={ref.id ?? `ref-${ri}`} data-testid="ai-ref-row">
                        {href ? (
                          <Link to={href} className="nav-link">
                            {label}
                          </Link>
                        ) : (
                          // No structured id for this type → no destination. Text,
                          // not a link that would open something else.
                          <span className="muted">{label}</span>
                        )}
                        <span className="muted">
                          {' '}
                          · {typeLabel}
                          {/* Display only: the server's own human label. */}
                          {ref.locator ? ` · ${ref.locator}` : ''}
                        </span>
                        {ref.snippet ? (
                          <span className="muted">
                            {' '}
                            — {ref.snippet.slice(0, 80)}
                          </span>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              </div>
            )}
          </article>
        ))}

        {busy && (
          <article className="ai-message ai-message--assistant ai-message--pending">
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
        {/* A3.2 / G07: what travels with this message is listed before it is
            sent, in the same words as the ids in the body — and the empty case
            is stated too, so the learner is never left guessing whether the
            source they came from is still attached. */}
        {attachedLabels.length > 0 ? (
          <p className="page__note" data-testid="ai-attach-summary">
            本条提问附带：{attachedLabels.join('、')}。
            <Button type="button" variant="secondary" onClick={onClearAttachment}>
              不附带
            </Button>
          </p>
        ) : (
          <p className="page__note" data-testid="ai-attach-none">
            本条提问不附带任何材料。
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
          aria-label="消息"
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
