/**
 * Knowledge Catalog UI (FE-001 PHASE I).
 *
 * /spaces/:spaceId/knowledge — category tree (left) + knowledge point
 * list (right). Pure tree building lives in lib/category-tree.ts;
 * this component renders it defensively (cycles cannot recurse forever
 * because buildCategoryTree already guards them).
 */

import { useState } from 'react';
import { KNOWLEDGE_FORM_LIMITS } from '../../lib/format';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { buildCategoryTree } from '../../lib/category-tree';
import {
  isPositiveId,
  parseOptionalInteger,
  parseOptionalPositiveId,
} from '../../lib/ids';
import { useFormError } from '../../lib/use-form-error';
import type { CategoryNode } from '../../lib/category-tree';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { originLabel } from '../../lib/format';
import { SelectMenu } from '../../components/SelectMenu';
import { StatusBadge } from '../../components/StatusBadge';
import { TextArea } from '../../components/TextArea';

export function KnowledgePage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const spaceId = useSpaceId();
  const [creatingCategory, setCreatingCategory] = useState(false);
  const [creatingPoint, setCreatingPoint] = useState(false);

  const categoriesQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.knowledgeCategories(spaceId),
      (id) => api.listKnowledgeCategories(id)
    )
  );

  const pointsQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.knowledgePoints(spaceId),
      (id) => api.listKnowledgePoints(id)
    )
  );

  const createCategory = useMutation({
    mutationFn: (body: {
      name: string;
      description?: string;
      parentId?: number;
      sortOrder?: number;
    }) =>
      api
        .createKnowledgeCategory(requireId(spaceId, 'space id'), body)
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgeCategories(spaceId),
      });
      setCreatingCategory(false);
    },
  });

  const createPoint = useMutation({
    mutationFn: (body: {
      title: string;
      summary?: string;
      content: string;
      categoryId?: number;
      difficulty?: string;
    }) =>
      api
        .createKnowledgePoint(requireId(spaceId, 'space id'), body)
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoints(spaceId),
      });
      setCreatingPoint(false);
    },
  });

  if (!spaceId) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const categories = categoriesQuery.data ?? [];
  const points = pointsQuery.data ?? [];
  const categoryById = new Map<number, string>();
  for (const category of categories) {
    if (category.id !== undefined && category.name) {
      categoryById.set(category.id, category.name);
    }
  }
  const tree = buildCategoryTree(categories);

  const loading = categoriesQuery.isPending || pointsQuery.isPending;
  const error = categoriesQuery.isError
    ? categoriesQuery.error
    : pointsQuery.isError
      ? pointsQuery.error
      : null;

  return (
    <div className="page">
      <PageHeader
        title="知识库"
        actions={
          <>
            <Button onClick={() => setCreatingCategory(true)}>
              创建分类
            </Button>
            <Button variant="primary" onClick={() => setCreatingPoint(true)}>
              创建知识点
            </Button>
          </>
        }
      />

      {loading && <LoadingState text="加载知识库…" />}

      {error && (
        <ErrorState
          message={normalizeApiError(error).message}
          onRetry={() => {
            void categoriesQuery.refetch();
            void pointsQuery.refetch();
          }}
        />
      )}

      {!loading && !error && (
        <div className="knowledge-layout">
          <section className="knowledge-layout__tree" aria-label="分类树">
            <h2 className="section-title">分类</h2>
            {categories.length === 0 ? (
              <p className="muted">还没有分类。</p>
            ) : (
              <CategoryTree nodes={tree} />
            )}
          </section>

          <section className="knowledge-layout__points" aria-label="知识点">
            <h2 className="section-title">知识点</h2>
            {points.length === 0 ? (
              <EmptyState
                title="还没有知识点"
                description="先创建一个知识点，开始搭建你的知识体系。"
                action={
                  <Button variant="primary" onClick={() => setCreatingPoint(true)}>
                    创建知识点
                  </Button>
                }
              />
            ) : (
              <ul className="point-list">
                {points.map((point, index) => {
                  const pointId = point.id;
                  const navigable = isPositiveId(pointId);
                  const content = (
                    <>
                      <span className="point-list__title">
                        {point.title ?? '未命名'}
                      </span>
                      {point.summary && (
                        <span className="point-list__summary">
                          {point.summary}
                        </span>
                      )}
                      <span className="point-list__meta">
                        <StatusBadge status={point.status} />
                        {point.categoryId !== undefined &&
                          categoryById.get(point.categoryId) && (
                            <span className="badge badge--unknown">
                              {categoryById.get(point.categoryId)}
                            </span>
                          )}
                        {point.originType && (
                          <span className="badge badge--unknown">
                            {originLabel(point.originType)}
                          </span>
                        )}
                        {point.difficulty && (
                          <span className="badge badge--unknown">
                            {point.difficulty}
                          </span>
                        )}
                      </span>
                    </>
                  );
                  return (
                    <li key={pointId ?? `point-${index}`}>
                      {navigable ? (
                        <button
                          type="button"
                          className="point-list__item"
                          onClick={() =>
                            void navigate(
                              `/spaces/${spaceId}/knowledge/${pointId}`
                            )
                          }
                        >
                          {content}
                        </button>
                      ) : (
                        <div className="point-list__item point-list__item--static">
                          {content}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>
      )}

      {creatingCategory && (
        <CreateCategoryDialog
          categories={categories}
          onClose={() => setCreatingCategory(false)}
          onSubmit={(body) => createCategory.mutate(body)}
          pending={createCategory.isPending}
          error={
            createCategory.isError
              ? normalizeApiError(createCategory.error)
              : null
          }
        />
      )}

      {creatingPoint && (
        <CreatePointDialog
          categories={categories}
          onClose={() => setCreatingPoint(false)}
          onSubmit={(body) => createPoint.mutate(body)}
          pending={createPoint.isPending}
          error={
            createPoint.isError ? normalizeApiError(createPoint.error) : null
          }
        />
      )}
    </div>
  );
}

function CategoryTree({ nodes }: { nodes: CategoryNode[] }) {
  return (
    <ul className="category-tree">
      {nodes.map((node) => (
        <CategoryTreeItem key={node.category.id ?? 'orphan'} node={node} />
      ))}
    </ul>
  );
}

function CategoryTreeItem({ node }: { node: CategoryNode }) {
  const hasChildren = node.children.length > 0;
  return (
    <li className="category-tree__item">
      <span className="category-tree__label">
        {hasChildren ? '▾' : '·'} {node.category.name ?? '未命名'}
        {node.category.description && (
          <span className="category-tree__desc"> — {node.category.description}</span>
        )}
      </span>
      {hasChildren && (
        <ul className="category-tree">
          {node.children.map((child) => (
            <CategoryTreeItem
              key={child.category.id ?? 'orphan'}
              node={child}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function CreateCategoryDialog({
  categories,
  onClose,
  onSubmit,
  pending,
  error,
}: {
  categories: { id?: number; name?: string; parentId?: number }[];
  onClose: () => void;
  onSubmit: (body: {
    name: string;
    description?: string;
    parentId?: number;
    sortOrder?: number;
  }) => void;
  pending: boolean;
  error: { message: string } | null;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [parentId, setParentId] = useState('');
  const [sortOrder, setSortOrder] = useState('');
  const formError = useFormError(error);

  return (
    <Dialog title="创建分类" onClose={onClose} busy={pending}>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!name.trim()) {
            return;
          }
          onSubmit({
            name: name.trim(),
            description: description.trim() || undefined,
            parentId: parseOptionalPositiveId(parentId),
            sortOrder: parseOptionalInteger(sortOrder),
          });
        }}
      >
        <Input
          label="名称"
          required
          maxLength={KNOWLEDGE_FORM_LIMITS.categoryNameMaxLength}
          value={name}
          onChange={(event) => {
            setName(event.target.value);
            formError.clear();
          }}
        />
        <Input
          label="说明（可选）"
          maxLength={KNOWLEDGE_FORM_LIMITS.categoryDescriptionMaxLength}
          value={description}
          onChange={(event) => {
            setDescription(event.target.value);
            formError.clear();
          }}
        />
        <SelectMenu
          label="父分类（可选）"
          value={parentId}
          onValueChange={(value) => {
            setParentId(value);
            formError.clear();
          }}
          options={[
            // The empty value is the root: `parentId` is parsed with
            // parseOptionalPositiveId, so '' is omitted from the payload and the
            // category is created at the top level.
            { value: '', label: '(none — root category)' },
            // Only current-space categories with a usable id are offered; a
            // malformed id could not be sent as a parentId anyway.
            ...categories
              .filter((category) => isPositiveId(category.id))
              .map((category) => ({
                value: String(category.id),
                label: category.name ?? `#${category.id}`,
              })),
          ]}
        />
        <Input
          label="排序（可选）"
          type="number"
          value={sortOrder}
          onChange={(event) => {
            setSortOrder(event.target.value);
            formError.clear();
          }}
        />
        {formError.message && (
          <p className="form__error" role="alert">{formError.message}</p>
        )}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={pending || !name.trim()}>
            {pending ? '创建中…' : '创建'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            取消
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function CreatePointDialog({
  categories,
  onClose,
  onSubmit,
  pending,
  error,
}: {
  categories: { id?: number; name?: string }[];
  onClose: () => void;
  onSubmit: (body: {
    title: string;
    summary?: string;
    content: string;
    categoryId?: number;
    difficulty?: string;
  }) => void;
  pending: boolean;
  error: { message: string } | null;
}) {
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [content, setContent] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [difficulty, setDifficulty] = useState('');
  const formError = useFormError(error);

  return (
    <Dialog title="创建知识点" onClose={onClose} busy={pending}>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!title.trim() || !content.trim()) {
            return;
          }
          onSubmit({
            title: title.trim(),
            summary: summary.trim() || undefined,
            content: content.trim(),
            categoryId: parseOptionalPositiveId(categoryId),
            difficulty: difficulty.trim() || undefined,
          });
        }}
      >
        <Input
          label="标题"
          required
          maxLength={KNOWLEDGE_FORM_LIMITS.pointTitleMaxLength}
          value={title}
          onChange={(event) => {
            setTitle(event.target.value);
            formError.clear();
          }}
        />
        <Input
          label="摘要（可选）"
          maxLength={KNOWLEDGE_FORM_LIMITS.pointSummaryMaxLength}
          value={summary}
          onChange={(event) => {
            setSummary(event.target.value);
            formError.clear();
          }}
        />
        <TextArea
          label="正文"
          required
          rows={6}
          value={content}
          onChange={(event) => {
            setContent(event.target.value);
            formError.clear();
          }}
        />
        <SelectMenu
          label="分类（可选）"
          value={categoryId}
          onValueChange={(value) => {
            setCategoryId(value);
            formError.clear();
          }}
          options={[
            // '' parses to undefined, so the field is left out of the payload
            // and the point is created uncategorised.
            { value: '', label: '(none)' },
            // Only ids this space's own category list returned: an option value
            // is the only way a categoryId can reach the request body.
            ...categories
              .filter((category) => isPositiveId(category.id))
              .map((category) => ({
                value: String(category.id),
                label: category.name ?? `#${category.id}`,
              })),
          ]}
        />
        <Input
          label="难度（可选，自由填写）"
          maxLength={KNOWLEDGE_FORM_LIMITS.pointDifficultyMaxLength}
          value={difficulty}
          onChange={(event) => {
            setDifficulty(event.target.value);
            formError.clear();
          }}
        />
        {formError.message && (
          <p className="form__error" role="alert">{formError.message}</p>
        )}
        <div className="form__actions">
          <Button
            type="submit"
            variant="primary"
            disabled={pending || !title.trim() || !content.trim()}
          >
            {pending ? '创建中…' : '创建'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            取消
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
