import { useBlocker } from 'react-router-dom';
import { Button } from './Button';
import { Dialog } from './Dialog';

/**
 * Blocks in-app navigation while an editor holds unsaved content (A8.2).
 *
 * `useBlocker` only exists on a data router: the app uses `createHashRouter`,
 * and its tests mount this through `createMemoryRouter`. The guard renders
 * nothing until a navigation is actually intercepted, so a clean editor keeps
 * normal back/forward behaviour.
 */
export function UnsavedChangesGuard({ isDirty }: { isDirty: boolean }) {
  const blocker = useBlocker(isDirty);

  if (blocker.state !== 'blocked') {
    return null;
  }

  return (
    <Dialog title="有未保存的内容" onClose={() => blocker.reset?.()}>
      <p>离开此页面会丢弃尚未保存的修改。</p>
      <div className="dialog__actions">
        <Button type="button" variant="primary" onClick={() => blocker.reset?.()}>
          留在本页
        </Button>
        <Button type="button" variant="danger" onClick={() => blocker.proceed()}>
          放弃修改并离开
        </Button>
      </div>
    </Dialog>
  );
}
