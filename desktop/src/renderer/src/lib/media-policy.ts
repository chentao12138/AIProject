/**
 * Media classification + size pre-check + failure-code mapping
 * (FE-002B Batch C stable contract).
 *
 * Backend remains authoritative. These helpers are usability filters
 * and safe UI copy only — they never replace server validation.
 *
 * Hard limits (stable contract):
 *   PDF   .pdf            application/pdf | application/octet-stream
 *         max 100MB / 200 pages / 2,000,000 chars
 *         encrypted → FAILED PDF_ENCRYPTED
 *         image-only → SUCCEEDED + BODY page + empty text + 0 blocks
 *   Image .png .jpg .jpeg image/png | image/jpeg | application/octet-stream
 *         max 50MB / 10000×10000 / 100,000,000 px
 *         success → SUCCEEDED + IMAGE page + 0 blocks + extractedText=null
 *         NO OCR, NO WebP
 *   Text  .txt .md .markdown
 *         FE-002A behavior unchanged
 */

export type MediaKind = 'text' | 'markdown' | 'pdf' | 'image' | 'unknown';

export interface MediaPolicy {
  kind: MediaKind;
  /** Human label for UI. */
  label: string;
  /** Max upload bytes; undefined = no frontend pre-check. */
  maxBytes?: number;
}

const PDF_MAX_BYTES = 100 * 1024 * 1024;
const IMAGE_MAX_BYTES = 50 * 1024 * 1024;
const TEXT_MAX_BYTES = 64 * 1024 * 1024;

const EXT_POLICIES: Record<string, MediaPolicy> = {
  pdf: { kind: 'pdf', label: 'PDF', maxBytes: PDF_MAX_BYTES },
  png: { kind: 'image', label: 'PNG image', maxBytes: IMAGE_MAX_BYTES },
  jpg: { kind: 'image', label: 'JPEG image', maxBytes: IMAGE_MAX_BYTES },
  jpeg: { kind: 'image', label: 'JPEG image', maxBytes: IMAGE_MAX_BYTES },
  txt: { kind: 'text', label: 'TXT', maxBytes: TEXT_MAX_BYTES },
  md: { kind: 'markdown', label: 'Markdown', maxBytes: TEXT_MAX_BYTES },
  markdown: { kind: 'markdown', label: 'Markdown', maxBytes: TEXT_MAX_BYTES },
};

/** Accept attribute for <input type="file">. */
export const MEDIA_ACCEPT_ATTR =
  '.txt,.md,.markdown,.pdf,.png,.jpg,.jpeg,' +
  'text/plain,text/markdown,application/pdf,image/png,image/jpeg';

/**
 * Classify a browser File by extension (case-insensitive).
 * MIME is advisory only — browsers report inconsistently.
 */
export function classifyMediaFile(file: { name: string }): MediaPolicy {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  return EXT_POLICIES[ext] ?? { kind: 'unknown', label: 'File' };
}

/**
 * Usability size pre-check. Returns an error message when the file
 * is known-too-large for its type, or null when acceptable.
 * Backend is still the final authority.
 */
export function precheckFileSize(file: {
  name: string;
  size: number;
}): string | null {
  const policy = classifyMediaFile(file);
  if (policy.maxBytes === undefined) {
    return null;
  }
  if (file.size > policy.maxBytes) {
    const limit = formatLimit(policy.maxBytes);
    return `${policy.label} 文件不能超过 ${limit}（当前 ${formatLimit(file.size)}）。`;
  }
  return null;
}

function formatLimit(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    const mb = bytes / (1024 * 1024);
    return `${Number.isInteger(mb) ? mb : mb.toFixed(1)} MB`;
  }
  return `${Math.round(bytes / 1024)} KB`;
}

/**
 * Stable backend failure-code → safe user-facing Chinese copy.
 * Unknown codes fall back to a neutral message. Never shows raw
 * backend exceptions or stack traces.
 */
export function describeIngestionErrorCode(
  errorCode?: string | null,
  errorMessage?: string | null
): string {
  const code = (errorCode ?? '').trim().toUpperCase();
  const map: Record<string, string> = {
    PDF_ENCRYPTED: '该 PDF 已加密，无法提取文本。请选择未加密的 PDF。',
    INVALID_PDF: 'PDF 文件格式无效或已损坏。',
    PDF_PAGE_LIMIT_EXCEEDED: 'PDF 页数超过 200 页上限。',
    PDF_TEXT_LIMIT_EXCEEDED: 'PDF 提取文本超过 2,000,000 字符上限。',
    PDF_PARSE_FAILED: 'PDF 解析失败，请检查文件是否有效。',
    INVALID_IMAGE: '图片文件格式无效或已损坏。',
    IMAGE_DIMENSION_LIMIT_EXCEEDED: '图片宽高超过 10000×10000 上限。',
    IMAGE_PIXEL_LIMIT_EXCEEDED: '图片总像素超过 1 亿上限。',
    IMAGE_TOO_LARGE: '图片文件超过 50MB 上限。',
    IMAGE_PARSE_FAILED: '图片解析失败，请检查文件是否有效。',
    UNSUPPORTED_FORMAT: '不支持的文件格式。',
    DOCUMENT_TOO_LARGE: '文件超过服务器大小上限。',
    ENCODING_ERROR: '文件编码无法识别。',
    ZIP_SAFETY_VIOLATION: 'ZIP 安全检查未通过。',
  };
  const mapped = map[code];
  if (mapped) {
    return mapped;
  }
  // Neutral fallback — prefer the safe errorMessage if backend sent one,
  // otherwise a generic line. Never leak stack traces.
  if (errorMessage && errorMessage.length < 200 && !errorMessage.includes('\n')) {
    return errorMessage;
  }
  return '文件处理失败，请稍后重试或更换文件。';
}

/**
 * True when a SUCCEEDED job produced an image-only PDF: BODY page(s)
 * with empty/blank extractedText and zero content blocks.
 * This is SUCCESS, not error.
 */
export function isImageOnlyPdfSuccess(params: {
  status?: string | null;
  pages: { pageType?: string; extractedText?: string | null }[];
  blockCount: number;
}): boolean {
  const status = (params.status ?? '').trim().toUpperCase();
  if (status !== 'SUCCEEDED') {
    return false;
  }
  if (params.blockCount > 0) {
    return false;
  }
  if (params.pages.length === 0) {
    return false;
  }
  return params.pages.every((p) => {
    const text = (p.extractedText ?? '').trim();
    return text === '';
  });
}

/**
 * True when a SUCCEEDED job produced a standalone image:
 * exactly one IMAGE SourcePage, zero blocks, extractedText null/empty.
 * This is SUCCESS, not error. No OCR in V1.
 */
export function isStandaloneImageSuccess(params: {
  status?: string | null;
  pages: { pageType?: string; extractedText?: string | null }[];
  blockCount: number;
}): boolean {
  const status = (params.status ?? '').trim().toUpperCase();
  if (status !== 'SUCCEEDED') {
    return false;
  }
  if (params.blockCount > 0) {
    return false;
  }
  if (params.pages.length !== 1) {
    return false;
  }
  const page = params.pages[0];
  return (page.pageType ?? '').trim().toUpperCase() === 'IMAGE';
}
