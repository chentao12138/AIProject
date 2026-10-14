/**
 * Runtime API base URL resolution (ADR-051).
 */

import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_API_BASE_URL } from '../shared/api-config';
import {
  API_BASE_URL_ENV_VAR,
  RUNTIME_CONFIG_FILE_NAME,
} from '../shared/runtime-config';
import { resolveRuntimeApiBaseUrl } from './runtime-config';

function resolve(options: {
  env?: string;
  file?: string | null;
  buildDefault?: string | null;
}) {
  const readEnv = vi.fn((name: string) =>
    name === API_BASE_URL_ENV_VAR ? options.env : undefined
  );
  const readFile = vi.fn((path: string) => {
    if (options.file === null) {
      throw new Error(`ENOENT: ${path}`);
    }
    return options.file ?? '';
  });
  return {
    resolved: resolveRuntimeApiBaseUrl({
      userDataPath: () => 'C:/UserData/AIStudy',
      buildDefault: options.buildDefault,
      readEnv,
      readFile,
    }),
    readEnv,
    readFile,
  };
}

describe('resolveRuntimeApiBaseUrl', () => {
  it('prefers the environment over the config file and the build default', () => {
    const { resolved, readFile } = resolve({
      env: 'https://env.example.com:9000/api',
      file: JSON.stringify({ apiBaseUrl: 'https://file.example.com' }),
      buildDefault: 'http://127.0.0.1:9090',
    });

    expect(resolved).toEqual({
      url: 'https://env.example.com:9000/api',
      source: 'env',
    });
    // The env value won, so the file must not have been consulted at all.
    expect(readFile).not.toHaveBeenCalled();
  });

  it('uses the config file when no env override exists', () => {
    const { resolved } = resolve({
      file: JSON.stringify({ apiBaseUrl: 'https://api.example.com/' }),
      buildDefault: 'http://127.0.0.1:9090',
    });

    expect(resolved).toEqual({
      url: 'https://api.example.com',
      source: 'config-file',
    });
  });

  it('accepts a config file that starts with a UTF-8 BOM', () => {
    // Windows PowerShell 5.1 `Set-Content -Encoding utf8` — the documented way
    // to create this file on the target platform — writes a BOM. Discarding the
    // file over it would silently send the app to the packaged default URL.
    const { resolved } = resolve({
      file: '\uFEFF' + JSON.stringify({ apiBaseUrl: 'http://localhost:8081' }),
      buildDefault: 'http://127.0.0.1:9090',
    });

    expect(resolved).toEqual({
      url: 'http://localhost:8081',
      source: 'config-file',
    });
  });

  it('falls back to the build default when nothing is configured', () => {
    const { resolved } = resolve({ file: null, buildDefault: 'http://127.0.0.1:9090' });
    expect(resolved).toEqual({
      url: 'http://127.0.0.1:9090',
      source: 'build-default',
    });
  });

  it('falls back to the hard default on a clean checkout', () => {
    const { resolved } = resolve({ file: null, buildDefault: undefined });
    expect(resolved).toEqual({
      url: DEFAULT_API_BASE_URL,
      source: 'hard-default',
    });
  });

  it('rejects a non-http(s) env value instead of trusting it', () => {
    const { resolved } = resolve({
      env: 'file:///etc/passwd',
      file: JSON.stringify({ apiBaseUrl: 'https://api.example.com' }),
    });
    expect(resolved.source).toBe('config-file');
  });

  it('ignores a malformed or unrelated config file', () => {
    for (const file of ['{ not json', '[]', '{"other":"x"}', '{"apiBaseUrl":""}']) {
      const { resolved } = resolve({ file, buildDefault: 'http://127.0.0.1:9090' });
      expect(resolved).toEqual({
        url: 'http://127.0.0.1:9090',
        source: 'build-default',
      });
    }
  });

  it('reads the config file from userData only', () => {
    const { readFile } = resolve({ file: '{}' });
    expect(readFile).toHaveBeenCalledWith(
      join('C:/UserData/AIStudy', RUNTIME_CONFIG_FILE_NAME)
    );
  });
});
