import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link } from 'react-router-dom';
import { UnsavedChangesGuard } from './UnsavedChangesGuard';
import { renderWithRouterProvider } from '../test/router-utils';

function EditorPage({ isDirty }: { isDirty: boolean }) {
  // Dirty state lives in the page that owns the editor; the guard is a
  // separate leaf so a re-render of the form never re-arms the blocker.
  const [dirty] = useState(isDirty);
  return (
    <div>
      <Link to="/other">离开</Link>
      <UnsavedChangesGuard isDirty={dirty} />
    </div>
  );
}

function renderGuarded(isDirty: boolean) {
  return renderWithRouterProvider(
    [
      { path: '/editor', element: <EditorPage isDirty={isDirty} /> },
      { path: '/other', element: <div>其它页面</div> },
    ],
    { initialEntries: ['/editor'] }
  );
}

describe('UnsavedChangesGuard (A8.2)', () => {
  it('intercepts navigation while content is unsaved and lets the user stay', async () => {
    const user = userEvent.setup();
    renderGuarded(true);

    await user.click(await screen.findByRole('link', { name: '离开' }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByText('其它页面')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '留在本页' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('其它页面')).not.toBeInTheDocument();
  });

  it('proceeds only after the user confirms discarding the content', async () => {
    const user = userEvent.setup();
    renderGuarded(true);

    await user.click(await screen.findByRole('link', { name: '离开' }));
    await user.click(await screen.findByRole('button', { name: '放弃修改并离开' }));

    expect(await screen.findByText('其它页面')).toBeInTheDocument();
  });

  it('does not intercept navigation when nothing is dirty', async () => {
    const user = userEvent.setup();
    renderGuarded(false);

    await user.click(await screen.findByRole('link', { name: '离开' }));

    expect(await screen.findByText('其它页面')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
