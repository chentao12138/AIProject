/**
 * Knowledge Catalog UI (FE-001 PHASE I).
 *
 * /spaces/:spaceId/knowledge — category tree (left) + knowledge point
 * list (right). Pure tree building lives in lib/category-tree.ts;
 * this component renders it defensively (cycles cannot recurse forever
 * because buildCategoryTree already guards them).
 */

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import { unwrap, normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { buildCategoryTree } from '../../lib/category-tree';
import type { CategoryNode } from '../../lib/category-tree';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { Select } from '../../components/Select';
import { StatusBadge } from '../../components/StatusBadge';
import { TextArea } from '../../components/TextArea';

export function KnowledgePage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam } = useParams();
  const [creatingCategory, setCreatingCategory] = useState(false);
  const [creatingPoint, setCreatingPoint] = useState(false);

  const spaceId = Number(spaceIdParam);
  const spaceIdValid = Number.isFinite(spaceId) && spaceId > 0;

  const categoriesQuery = useQuery({
    queryKey: queryKeys.knowledgeCategories(spaceId),
    queryFn: () => api.listKnowledgeCategories(spaceId).then(unwrap),
    enabled: spaceIdValid,
  });

  const pointsQuery = useQuery({
    queryKey: queryKeys.knowledgePoints(spaceId),
    queryFn: () => api.listKnowledgePoints(spaceId).then(unwrap),
    enabled: spaceIdValid,
  });

  const createCategory = useMutation({
    mutationFn: (body: {
      name: string;
      description?: string;
      parentId?: number;
      sortOrder?: number;
    }) => api.createKnowledgeCategory(spaceId, body).then(unwrap),
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
    }) => api.createKnowledgePoint(spaceId, body).then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoints(spaceId),
      });
      setCreatingPoint(false);
    },
  });

  if (!spaceIdValid) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
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
      <div className="page__header">
        <h1 className="page__title">Knowledge</h1>
        <div className="page__actions">
          <Button onClick={() => setCreatingCategory(true)}>
            Create Category
          </Button>
          <Button variant="primary" onClick={() => setCreatingPoint(true)}>
            Create Knowledge Point
          </Button>
        </div>
      </div>

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
          <section className="knowledge-layout__tree" aria-label="Category tree">
            <h2 className="section-title">Categories</h2>
            {categories.length === 0 ? (
              <p className="muted">No categories yet.</p>
            ) : (
              <CategoryTree nodes={tree} />
            )}
          </section>

          <section className="knowledge-layout__points" aria-label="Knowledge points">
            <h2 className="section-title">Points</h2>
            {points.length === 0 ? (
              <EmptyState
                title="No knowledge points yet"
                description="Create a knowledge point to start building your catalog."
                action={
                  <Button variant="primary" onClick={() => setCreatingPoint(true)}>
                    Create Knowledge Point
                  </Button>
                }
              />
            ) : (
              <ul className="point-list">
                {points.map((point, index) => {
                  const pointId = point.id;
                  const navigable =
                    typeof pointId === 'number' &&
                    Number.isFinite(pointId) &&
                    pointId > 0;
                  const content = (
                    <>
                      <span className="point-list__title">
                        {point.title ?? 'Untitled'}
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
                            {point.originType}
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
        <CategoryTreeItem key={node.category.id ?? 'orphan'} node={node} depth={0} />
      ))}
    </ul>
  );
}

function CategoryTreeItem({ node, depth }: { node: CategoryNode; depth: number }) {
  const hasChildren = node.children.length > 0;
  return (
    <li className="category-tree__item">
      <span
        className="category-tree__label"
        style={{ paddingLeft: `${depth * 14}px` }}
      >
        {hasChildren ? '▾' : '·'} {node.category.name ?? 'Untitled'}
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
              depth={depth + 1}
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

  return (
    <Dialog title="Create Category" onClose={onClose}>
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
            parentId:
              parentId !== '' && Number.isFinite(Number(parentId))
                ? Number(parentId)
                : undefined,
            sortOrder:
              sortOrder !== '' && Number.isFinite(Number(sortOrder))
                ? Number(sortOrder)
                : undefined,
          });
        }}
      >
        <Input
          label="Name"
          required
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <Input
          label="Description (optional)"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        <Select
          label="Parent category (optional)"
          value={parentId}
          onChange={(event) => setParentId(event.target.value)}
        >
          <option value="">(none — root category)</option>
          {categories.map((category, index) => (
            <option
              key={category.id ?? `cat-${index}`}
              value={category.id ?? ''}
            >
              {category.name ?? `#${category.id ?? 'unknown'}`}
            </option>
          ))}
        </Select>
        <Input
          label="Sort order (optional)"
          type="number"
          value={sortOrder}
          onChange={(event) => setSortOrder(event.target.value)}
        />
        {error && <p className="form__error" role="alert">{error.message}</p>}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={pending || !name.trim()}>
            {pending ? 'Creating…' : 'Create'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            Cancel
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

  return (
    <Dialog title="Create Knowledge Point" onClose={onClose}>
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
            categoryId:
              categoryId !== '' && Number.isFinite(Number(categoryId))
                ? Number(categoryId)
                : undefined,
            difficulty: difficulty.trim() || undefined,
          });
        }}
      >
        <Input
          label="Title"
          required
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
        <Input
          label="Summary (optional)"
          value={summary}
          onChange={(event) => setSummary(event.target.value)}
        />
        <TextArea
          label="Content"
          required
          rows={6}
          value={content}
          onChange={(event) => setContent(event.target.value)}
        />
        <Select
          label="Category (optional)"
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
        >
          <option value="">(none)</option>
          {categories.map((category, index) => (
            <option
              key={category.id ?? `cat-${index}`}
              value={category.id ?? ''}
            >
              {category.name ?? `#${category.id ?? 'unknown'}`}
            </option>
          ))}
        </Select>
        <Input
          label="Difficulty (optional free text)"
          value={difficulty}
          onChange={(event) => setDifficulty(event.target.value)}
        />
        {error && <p className="form__error" role="alert">{error.message}</p>}
        <div className="form__actions">
          <Button
            type="submit"
            variant="primary"
            disabled={pending || !title.trim() || !content.trim()}
          >
            {pending ? 'Creating…' : 'Create'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
