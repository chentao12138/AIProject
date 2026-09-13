/**
 * Renderer-level React Error Boundary (FE-001.5 STRETCH A).
 *
 * Catches unexpected render failures so the desktop window never goes
 * blank: shows a safe recovery screen with a retry action. Production
 * UI never exposes error messages or stack traces — details are logged
 * to the console in DEV builds only.
 */

import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

const IS_DEV = import.meta.env.DEV;

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Diagnostics for development only — never rendered into the UI.
    if (IS_DEV) {
      console.error('[ErrorBoundary]', error, info.componentStack);
    }
  }

  private retry = (): void => {
    this.setState({ hasError: false });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="error-state" role="alert">
          <p className="error-state__message">页面出现异常，请重试。</p>
          <button
            type="button"
            className="btn btn--secondary"
            onClick={this.retry}
          >
            重试
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
