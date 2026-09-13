/**
 * AIStudy Desktop — Electron Main Process (FE-001 + ELECTRON-CORS-001-A).
 *
 * Security contract (ADR-037, docs/decisions.md):
 *  - contextIsolation: true, nodeIntegration: false, sandbox: true
 *  - window.open / new-window -> deny
 *  - will-navigate restricted to the application origin only
 *  - CSP enforced via response headers (single source, dev vs prod
 *    explicitly distinguished — no loose meta CSP)
 *  - Main holds NO business logic, NO database access, NO AI provider keys.
 *
 * Production renderer origin: app://aistudy (custom, non-opaque,
 * project-fixed scheme registered as standard+secure). file:// is no
 * longer used for the renderer, so the browser never sees Origin:null.
 *
 * FE-001 has zero business IPC surface (preload exposes nothing).
 */

import { app, BrowserWindow, net, protocol, session } from 'electron';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { cspFor } from './csp';
import { isPermissionGranted } from './permissions';
import { resolveApiBaseUrl } from '../shared/api-config';
import {
  APP_ORIGIN,
  APP_SCHEME,
  isAllowedAppNavigation,
  rendererRootPath,
  resolveAppUrlPath,
} from './app-protocol';

const DEV_SERVER_URL = process.env['ELECTRON_RENDERER_URL'];

// Single API base URL contract (FE-001.5 PRE-COMMIT REVIEW FIX-01):
// the SAME VITE_API_BASE_URL resolution the renderer client uses
// (electron-vite exposes VITE_* env vars to the main build too, and
// tsconfig.node.json types import.meta.env via vite/client). The CSP
// connect-src origin derives from it, so the policy always allows the
// origin the renderer actually talks to — never a hardcoded duplicate.
const API_BASE_URL = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

// Must run BEFORE app ready. standard+secure gives app://aistudy a real,
// non-opaque origin; supportFetchAPI lets the renderer fetch over it;
// corsEnabled makes the scheme participate in CORS as a normal origin.
// No bypassCSP, no allowServiceWorkers, no webSecurity:false.
protocol.registerSchemesAsPrivileged([
  {
    scheme: APP_SCHEME,
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
    },
  },
]);

function isAllowedNavigation(url: string): boolean {
  if (DEV_SERVER_URL) {
    // Dev: only the electron-vite renderer dev server origin.
    try {
      return new URL(url).origin === new URL(DEV_SERVER_URL).origin;
    } catch {
      return false;
    }
  }
  // Prod: ONLY app://aistudy (hash routes allowed; file://, http(s),
  // foreign app hosts all denied).
  return isAllowedAppNavigation(url);
}

function createMainWindow(): BrowserWindow {
  const mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    show: false,
    autoHideMenuBar: true,
    title: 'AIStudy',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
    },
  });

  mainWindow.on('ready-to-show', () => {
    mainWindow.show();
  });

  // B1: deny all new-window requests (no external URL in FE-001).
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  // B1: no <webview> embedding is ever allowed.
  mainWindow.webContents.on('will-attach-webview', (event) => {
    event.preventDefault();
  });

  // B1: block renderer navigation away from the application origin.
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!isAllowedNavigation(url)) {
      event.preventDefault();
    }
  });

  if (DEV_SERVER_URL) {
    void mainWindow.loadURL(DEV_SERVER_URL);
  } else {
    // Production: the custom origin, never file://.
    void mainWindow.loadURL(`${APP_ORIGIN}/`);
  }

  return mainWindow;
}

app.whenReady().then(() => {
  // Serve ONLY app://aistudy/** from the packaged renderer output.
  // Traversal / foreign-host / malformed URLs are rejected by
  // resolveAppUrlPath; net.fetch streams from disk (no whole-file
  // reads into memory).
  //
  // CSP is set DIRECTLY on the response here: session.webRequest does
  // not intercept protocol.handle responses, so the production policy
  // must ride on the app:// response itself (dev traffic keeps the
  // onHeadersReceived injection below).
  protocol.handle(APP_SCHEME, async (request) => {
    const filePath = resolveAppUrlPath(request.url, rendererRootPath());
    if (!filePath) {
      return new Response('Not found', { status: 404 });
    }
    const response = await net.fetch(pathToFileURL(filePath).toString());
    const headers = new Headers(response.headers);
    headers.set('Content-Security-Policy', cspFor(false, { apiBaseUrl: API_BASE_URL }));
    return new Response(response.body, {
      status: response.status,
      headers,
    });
  });

  // Dev CSP: injected on http(s) responses (Vite dev server, backend).
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    if (DEV_SERVER_URL) {
      callback({
        responseHeaders: {
          ...details.responseHeaders,
          'Content-Security-Policy': [
            cspFor(true, {
              devServerUrl: DEV_SERVER_URL ?? undefined,
              apiBaseUrl: API_BASE_URL,
            }),
          ],
        },
      });
    } else {
      callback({ responseHeaders: details.responseHeaders });
    }
  });

  // Deny-by-default browser permissions (FE-001.5 PHASE 19): no feature
  // needs camera/mic/geolocation/notifications/clipboard-read/etc.
  session.defaultSession.setPermissionRequestHandler(
    (_webContents, permission, callback) => {
      callback(isPermissionGranted(permission, ''));
    }
  );
  session.defaultSession.setPermissionCheckHandler(
    (_webContents, permission, requestingOrigin) => {
      return isPermissionGranted(permission, requestingOrigin);
    }
  );

  createMainWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
