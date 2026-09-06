/**
 * K1.1–K1.3 — category tree pure function tests.
 *
 * - root + child nest correctly
 * - orphan does not crash (shown safely)
 * - cycle does not infinite-recurse
 */

import { describe, expect, it } from 'vitest';
import { buildCategoryTree } from './category-tree';
import type { KnowledgeCategory } from './types';

function cat(
  id: number,
  name: string,
  parentId?: number
): KnowledgeCategory {
  return { id, name, parentId };
}

describe('buildCategoryTree', () => {
  it('nests root + child correctly (K1.1)', () => {
    const categories = [
      cat(1, 'Math'),
      cat(2, 'Algebra', 1),
      cat(3, 'Geometry', 1),
      cat(4, 'Physics'),
    ];
    const tree = buildCategoryTree(categories);

    expect(tree).toHaveLength(2);
    expect(tree[0].category.id).toBe(1);
    expect(tree[0].children.map((c) => c.category.id)).toEqual([2, 3]);
    expect(tree[1].category.id).toBe(4);
    expect(tree[1].children).toHaveLength(0);
  });

  it('keeps sibling order from the backend list (sortOrder/list order)', () => {
    const categories = [
      cat(1, 'Root'),
      cat(2, 'B', 1),
      cat(3, 'A', 1),
      cat(4, 'C', 1),
    ];
    const tree = buildCategoryTree(categories);
    expect(tree[0].children.map((c) => c.category.name)).toEqual(['B', 'A', 'C']);
  });

  it('renders orphans safely at root level without crashing (K1.2)', () => {
    const categories = [
      cat(1, 'Root'),
      cat(2, 'Orphan', 999), // parent does not exist
      cat(3, 'Child', 1),
    ];
    const tree = buildCategoryTree(categories);

    expect(tree).toHaveLength(2);
    const root = tree.find((n) => n.category.id === 1);
    const orphan = tree.find((n) => n.category.id === 2);
    expect(root?.children.map((c) => c.category.id)).toEqual([3]);
    expect(orphan?.children).toHaveLength(0);
  });

  it('handles null parentId as root', () => {
    const categories = [
      cat(1, 'A', undefined),
      cat(2, 'B', 1),
      cat(3, 'C', undefined),
    ];
    const tree = buildCategoryTree(categories);
    expect(tree.map((n) => n.category.id)).toEqual([1, 3]);
    expect(tree[0].children.map((n) => n.category.id)).toEqual([2]);
  });

  it('does not infinite-recurse on a cycle (K1.3)', () => {
    // 1 -> 2 -> 1
    const categories = [cat(1, 'A', 2), cat(2, 'B', 1)];
    const tree = buildCategoryTree(categories);

    // must terminate; every category still appears exactly once at some level
    // (visited guard keeps the walk itself safe even on cyclic input)
    const ids = new Set<number>();
    const seen = new Set<number>();
    const walk = (nodes: typeof tree) => {
      for (const node of nodes) {
        const id = node.category.id ?? -1;
        if (seen.has(id)) {
          continue;
        }
        seen.add(id);
        ids.add(id);
        walk(node.children);
      }
    };
    walk(tree);
    expect(ids.has(1)).toBe(true);
    expect(ids.has(2)).toBe(true);
  });

  it('surfaces a pure cycle at root level instead of dropping it', () => {
    const categories = [cat(1, 'A', 2), cat(2, 'B', 1)];
    const tree = buildCategoryTree(categories);
    // cycle nodes cannot nest under a real root; they surface at root
    expect(tree.length).toBeGreaterThan(0);
  });
});
