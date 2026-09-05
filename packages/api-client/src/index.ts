/**
 * @aistudy/api-client — shared OpenAPI-driven TypeScript client.
 *
 * Barrel export. Desktop (Electron) and Admin Web (React) both import
 * from this package so there is exactly ONE API contract
 * (api-guidelines.md §18: "TypeScript client 优先生成，不手工复制三套 DTO").
 */

export { createApiClient } from './client.js';
export type { ApiClient, TokenProvider } from './client.js';
