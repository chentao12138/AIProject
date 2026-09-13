/**
 * AIStudy Desktop — Preload (FE-001).
 *
 * FE-001 declares ZERO business IPC surface (no requirement -> no IPC,
 * per ADR-037 and FE-001 PHASE B2).
 *
 * Nothing is exposed to the renderer: no ipcRenderer, no fs, no path,
 * no process, no shell, no require. Revisit ONLY when FE-002 adds a
 * typed SourceAsset file picker via contextBridge.
 */
export {};
