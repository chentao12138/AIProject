/**
 * buildCategoryTree — pure function (FE-001 PHASE I1).
 *
 * Backend returns a flat category list; the UI builds a nested tree.
 *
 * Rules:
 *  - parentId == null  -> root
 *  - parentId present  -> nested child
 *  - siblings keep backend sortOrder / list order
 *  - orphan (parentId points to a missing category) -> displayed safely
 *    at root level (never crash, never infinite loop)
 *  - cycle (parentId chain loops) -> recursion is guarded with a
 *    visited set; the unreachable cycle node is still returned at root
 *    level so it stays visible instead of hanging the UI
 */

import type { KnowledgeCategory } from './types';

export interface CategoryNode {
  category: KnowledgeCategory;
  children: CategoryNode[];
}

export function buildCategoryTree(
  categories: KnowledgeCategory[]
): CategoryNode[] {
  const byId = new Map<number, CategoryNode>();
  for (const category of categories) {
    if (category.id === undefined) {
      continue;
    }
    byId.set(category.id, { category, children: [] });
  }

  const roots: CategoryNode[] = [];
  const byParent = new Map<number, CategoryNode[]>();

  for (const category of categories) {
    if (category.id === undefined) {
      continue;
    }
    const node = byId.get(category.id);
    if (!node) {
      continue;
    }
    const parentId = category.parentId;
    if (parentId === undefined || !byId.has(parentId)) {
      // root, or orphan with a missing/foreign parent — safe root display
      roots.push(node);
    } else {
      const siblings = byParent.get(parentId) ?? [];
      siblings.push(node);
      byParent.set(parentId, siblings);
    }
  }

  // Attach children depth-first; the visited set breaks any cycle so
  // recursion always terminates.
  const visited = new Set<number>();

  const attachChildren = (node: CategoryNode): void => {
    if (node.category.id === undefined || visited.has(node.category.id)) {
      return;
    }
    visited.add(node.category.id);
    // Filter children already visited in this traversal: the emitted tree
    // stays a DAG so recursive renderers can never loop on a cycle.
    const children = (byParent.get(node.category.id) ?? []).filter(
      (child) =>
        child.category.id !== undefined && !visited.has(child.category.id)
    );
    for (const child of children) {
      attachChildren(child);
    }
    node.children = children;
  };

  for (const root of roots) {
    attachChildren(root);
  }

  // Safety net: a pure cycle (A->B->A) never reached a root would
  // otherwise vanish — surface it at root level instead.
  for (const category of categories) {
    if (category.id !== undefined && !visited.has(category.id)) {
      const node = byId.get(category.id);
      if (node) {
        roots.push(node);
        attachChildren(node);
      }
    }
  }

  return roots;
}
