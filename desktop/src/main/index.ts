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

import {
  app,
  BrowserWindow,
  ipcMain,
  Menu,
  net,
  protocol,
  safeStorage,
  session,
} from 'electron';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { cspFor } from './csp';
import { isPermissionGranted } from './permissions';
import { buildWebPreferences } from './window-options';
import { registerAuthVaultIpc } from './auth-vault';
import { DEFAULT_API_BASE_URL } from '../shared/api-config';
import { APP_USER_DATA_NAME, resolveRuntimeApiBaseUrl } from './runtime-config';
import { registerAppConfigIpc } from './config-ipc';
import {
  APP_ORIGIN,
  APP_SCHEME,
  isAllowedAppNavigation,
  rendererRootPath,
  resolveAppUrlPath,
} from './app-protocol';

const DEV_SERVER_URL = process.env['ELECTRON_RENDERER_URL'];

// Single API base URL contract (FE-001.5 FIX-01, extended by ADR-051):
// main resolves the URL ONCE at startup (env -> userData/config.json ->
// build-time VITE_API_BASE_URL -> default) and is the only authority. The
// CSP connect-src origin is derived from that value and the renderer reads
// the very same value over a read-only IPC channel, so policy and traffic can
// never disagree — and the target is no longer welded into the binary.
let apiBaseUrl = DEFAULT_API_BASE_URL;

// Before anything that resolves a path — including the single-instance lock
// below, which lives in userData. Without this, a packaged build reads its
// config from `%APPDATA%\aistudy-desktop` while the installer, the Start-Menu
// entry and ADR-051 all say `AIStudy`, so an operator's config.json is silently
// never found and the CSP follows the stale default.
app.setName(APP_USER_DATA_NAME);

// One process must own the refresh-token vault: the vault is a single file
// slot and the backend rotates on every exchange, so a second instance
// restoring concurrently would reuse a rotated token and revoke the whole
// refresh family (ADR-026). Later launches just focus the running window.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const [window] = BrowserWindow.getAllWindows();
    if (!window) {
      return;
    }
    if (window.isMinimized()) {
      window.restore();
    }
    window.focus();
  });
}

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
    webPreferences: buildWebPreferences({
      preloadPath: join(__dirname, '../preload/index.js'),
      // A packaged renderer holds the access token in memory: DevTools would
      // be a read path for it, and the shipped product needs neither.
      allowDevTools: !app.isPackaged,
    }),
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
  // Resolution happens here (not at module scope) because userData is only
  // reliable once the app is ready.
  const runtime = resolveRuntimeApiBaseUrl({
    userDataPath: () => app.getPath('userData'),
    buildDefault: import.meta.env.VITE_API_BASE_URL,
  });
  apiBaseUrl = runtime.url;
  // Startup diagnostic: the shell header shows the same value, so a
  // mis-set/typo'd target is visible without a CSP forensics session.
  console.log(`[aistudy] API base URL: ${runtime.url} (source: ${runtime.source})`);

  // The default menu exposes reload / force-reload / toggle-devtools and is
  // summonable with Alt even with autoHideMenuBar; the product has no menu
  // commands, so a packaged build ships without one.
  if (app.isPackaged) {
    Menu.setApplicationMenu(null);
  }

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
    headers.set('Content-Security-Policy', cspFor(false, { apiBaseUrl }));
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
              apiBaseUrl,
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

  // FE-AUTH-001 (ADR-026): encrypted refresh-token vault. Only the
  // application's own renderer origin may call its IPC channels:
  // app://aistudy in production, additionally the Vite dev server in
  // development. safeStorage encrypts at rest via the OS keychain.
  const vaultAllowedOrigins = [APP_ORIGIN];
  if (DEV_SERVER_URL) {
    try {
      vaultAllowedOrigins.push(new URL(DEV_SERVER_URL).origin);
    } catch {
      // malformed dev URL: production origin only
    }
  }
  registerAuthVaultIpc({
    userDataPath: () => app.getPath('userData'),
    isEncryptionAvailable: () => safeStorage.isEncryptionAvailable(),
    encryptString: (plaintext) => safeStorage.encryptString(plaintext),
    decryptString: (ciphertext) => safeStorage.decryptString(ciphertext),
    handle: (channel, listener) => {
      ipcMain.handle(channel, listener);
    },
    allowedOrigins: vaultAllowedOrigins,
  });

  // ADR-051: publish the resolved target to the renderer (read-only, same
  // origin allowlist as the token vault).
  registerAppConfigIpc({
    apiBaseUrl: () => apiBaseUrl,
    allowedOrigins: vaultAllowedOrigins,
    handle: (channel, listener) => {
      ipcMain.handle(channel, listener);
    },
  });

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
