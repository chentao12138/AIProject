/**
 * Electron permission policy (FE-001.5 PHASE 19).
 *
 * Deny-by-default: no FE-001 feature requires ANY browser permission
 * (camera, microphone, geolocation, notifications, midi,
 * clipboard-read, pointerLock, media, fullscreen, ...).
 *
 * The session handlers delegate to this pure predicate so the policy
 * is unit-testable without an Electron runtime.
 *
 * NOTE: ordinary clipboard PASTE into the token input is NOT gated by
 * Electron permission handlers (it is the OS paste path through the
 * focused editable element); denying "clipboard-read" only blocks the
 * navigator.clipboard.readText() API surface, which nothing uses.
 */

/** Every permission the renderer could ever request is denied. */
export function isPermissionGranted(
  _permission: string,
  _origin: string
): boolean {
  return false;
}

/** Documentation of the denied surface (used by the static audit). */
export const DENIED_PERMISSIONS: readonly string[] = [
  'camera',
  'microphone',
  'geolocation',
  'notifications',
  'midi',
  'clipboard-read',
  'clipboard-sanitized-write',
  'pointerLock',
  'fullscreen',
  'media',
  'openExternal',
];
