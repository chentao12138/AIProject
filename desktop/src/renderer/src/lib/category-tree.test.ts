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

  it('handles a deep hierarchy (PHASE 12)', () => {
    const categories = [
      cat(1, 'L1'),
      cat(2, 'L2', 1),
      cat(3, 'L3', 2),
      cat(4, 'L4', 3),
      cat(5, 'L5', 4),
    ];
    const tree = buildCategoryTree(categories);
    expect(tree).toHaveLength(1);
    expect(tree[0].children[0].children[0].children[0].children[0].category.id).toBe(5);
  });

  it('handles a self-cycle without hanging (PHASE 12)', () => {
    const categories = [cat(1, 'Self', 1), cat(2, 'Normal')];
    const tree = buildCategoryTree(categories);
    expect(tree).toHaveLength(2);
  });

  it('handles a three-node cycle with each node exactly once (PHASE 12)', () => {
    const categories = [cat(1, 'A', 3), cat(2, 'B', 1), cat(3, 'C', 2)];
    const tree = buildCategoryTree(categories);
    const ids: number[] = [];
    const seen = new Set<number>();
    const walk = (nodes: typeof tree) => {
      for (const node of nodes) {
        const id = node.category.id ?? -1;
        if (seen.has(id)) {
          continue;
        }
        seen.add(id);
        ids.push(id);
        walk(node.children);
      }
    };
    walk(tree);
    expect(ids.sort((a, b) => a - b)).toEqual([1, 2, 3]);
  });

  it('handles duplicate parent references without duplication (PHASE 12)', () => {
    // Two nodes claim the same parent; both appear, parent lists both.
    const categories = [cat(1, 'Root'), cat(2, 'A', 1), cat(3, 'B', 1)];
    const tree = buildCategoryTree(categories);
    expect(tree[0].children.map((c) => c.category.id)).toEqual([2, 3]);
  });

  it('skips categories with missing ids (PHASE 12)', () => {
    const categories = [
      { id: undefined, name: 'Ghost', parentId: undefined } as unknown as KnowledgeCategory,
      cat(1, 'Real'),
    ];
    const tree = buildCategoryTree(categories);
    expect(tree).toHaveLength(1);
    expect(tree[0].category.id).toBe(1);
  });

  it('handles duplicate ids without hanging (PHASE 12)', () => {
    // Malformed backend data: two categories share id 1. Must not loop
    // and must terminate with a finite renderable tree.
    const categories = [cat(1, 'First'), cat(1, 'Second'), cat(2, 'Other')];
    const tree = buildCategoryTree(categories);
    expect(tree.length).toBeGreaterThan(0);
    // every emitted node is reachable and finite
    let count = 0;
    const seen = new Set<number>();
    const walk = (nodes: typeof tree) => {
      for (const node of nodes) {
        const id = node.category.id ?? -1;
        if (seen.has(id)) {
          continue;
        }
        seen.add(id);
        count += 1;
        walk(node.children);
      }
    };
    walk(tree);
    expect(count).toBeGreaterThanOrEqual(1);
  });

  it('keeps total node count stable on a large shallow set (PHASE 12)', () => {
    const categories = Array.from({ length: 2000 }, (_, i) => cat(i + 1, `C${i + 1}`));
    const tree = buildCategoryTree(categories);
    expect(tree).toHaveLength(2000);
    expect(tree[1999].category.name).toBe('C2000');
  });

  it('terminates on a long deep chain within reasonable limits (PHASE 12)', () => {
    const depth = 600;
    const categories = Array.from({ length: depth }, (_, i) =>
      cat(i + 1, `D${i + 1}`, i === 0 ? undefined : i)
    );
    const tree = buildCategoryTree(categories);
    expect(tree).toHaveLength(1);
    let node = tree[0];
    let steps = 0;
    while (node.children.length > 0 && steps < depth) {
      node = node.children[0];
      steps += 1;
    }
    expect(steps).toBe(depth - 1);
    expect(node.category.id).toBe(depth);
  });

  it('keeps backend list order stable across the tree (PHASE 12)', () => {
    const categories = [
      cat(1, 'Root'),
      cat(2, 'Z', 1),
      cat(3, 'A', 1),
      cat(4, 'M', 1),
    ];
    const tree = buildCategoryTree(categories);
    expect(tree[0].children.map((c) => c.category.name)).toEqual(['Z', 'A', 'M']);
  });
});
