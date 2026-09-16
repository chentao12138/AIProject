import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from './AppShell';
import { RequireAuth } from './RequireAuth';
import { RequireAdmin } from './RequireAdmin';
import { LoginPage } from '../features/auth/LoginPage';
import { UsersPage } from '../features/users/UsersPage';
import { OperationsPage } from '../features/operations/OperationsPage';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <RequireAdmin>
          <AppShell />
        </RequireAdmin>
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/users" replace /> },
      { path: 'users', element: <UsersPage /> },
      { path: 'operations', element: <OperationsPage /> },
      { path: '*', element: <Navigate to="/users" replace /> },
    ],
  },
]);
