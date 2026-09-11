/**
 * Permission policy (FE-001.5 PHASE 19).
 *
 * The desktop client requires zero browser permissions; every request
 * must be denied (request handler) and every check must fail (check
 * handler).
 */

import { describe, expect, it } from 'vitest';
import { DENIED_PERMISSIONS, isPermissionGranted } from './permissions';

describe('isPermissionGranted', () => {
  it('denies every permission in the documented surface', () => {
    for (const permission of DENIED_PERMISSIONS) {
      expect(isPermissionGranted(permission, 'app://aistudy')).toBe(false);
    }
  });

  it('denies unknown/future permission names', () => {
    expect(isPermissionGranted('garbage-permission', 'app://aistudy')).toBe(
      false
    );
    expect(isPermissionGranted('', '')).toBe(false);
  });

  it('denies regardless of requesting origin', () => {
    expect(isPermissionGranted('camera', 'https://evil.example')).toBe(false);
    expect(isPermissionGranted('geolocation', 'file:///x')).toBe(false);
  });
});
