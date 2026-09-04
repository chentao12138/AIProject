# API 规范

> 状态：**FINAL TECHNICAL + BUSINESS BASELINE**

## 1. 基本路径

业务 API：

```text
/api/v1/**
```

OpenAPI 默认：

```text
/v3/api-docs
```

## 2. LearningSpace API 作用域

space-scoped 资源优先显式放入路径：

```text
GET  /api/v1/spaces
POST /api/v1/spaces
GET  /api/v1/spaces/{spaceId}

GET  /api/v1/spaces/{spaceId}/sources
GET  /api/v1/spaces/{spaceId}/knowledge-points
GET  /api/v1/spaces/{spaceId}/questions
POST /api/v1/spaces/{spaceId}/practice-sessions
GET  /api/v1/spaces/{spaceId}/exams
GET  /api/v1/spaces/{spaceId}/mastery
GET  /api/v1/spaces/{spaceId}/study-plan
```

服务端必须验证 authenticated user 对 `spaceId` 的访问权。

禁止通过 query/header 让客户端自行声明 `userId` 作为所有权依据。

## 3. Admin API

Admin 专用：

```text
/api/v1/admin/**
```

例如：

```text
/api/v1/admin/users
/api/v1/admin/spaces
/api/v1/admin/spaces/{spaceId}/sources
/api/v1/admin/spaces/{spaceId}/ingestion-jobs
/api/v1/admin/spaces/{spaceId}/knowledge-points
/api/v1/admin/spaces/{spaceId}/questions
/api/v1/admin/spaces/{spaceId}/exams
```

所有 endpoint 服务端强制 ADMIN。

## 4. Source Upload

客户端不能发送：

```json
{"path":"D:\\学习资料\\book.zip"}
```

要求后端读取本地文件。

正式上传使用：

- `multipart/form-data`（第一版）
- 将来如真实大文件需要，再增加 chunk/resumable upload

示意：

```text
POST /api/v1/spaces/{spaceId}/sources/uploads
```

返回 SourceDocument 和 IngestionJob 标识。

文件名只用于展示，不作为物理 storage path。

## 5. Manual Source

```text
POST /api/v1/admin/spaces/{spaceId}/sources/manual
```

请求可包含：

- title
- content
- category/outline hints

创建 `ADMIN_MANUAL` SourceDocument。

管理员直接创建结构化 KnowledgePoint 使用对应 knowledge endpoint，并记录 `ADMIN_CURATED`，不要伪装成 Source。

## 6. Ingestion Job

```text
GET  /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}
POST /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/retry
GET  /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/issues
```

Admin 可有对应 `/admin/` 路径。

Job API 返回：

- stage/status
- progress
- safe error
- issues summary

不返回服务端 stack trace。

## 7. Review / Publish

具体 endpoint 可在实现时调整，但业务动作应明确，例如：

```text
PATCH /sources/{sourceId}/pages/{pageId}/order
PATCH /sources/{sourceId}/content-blocks/{id}
POST  /sources/{sourceId}/publish
POST  /knowledge-points/{id}/publish
```

不要做一个 generic：

```text
POST /admin/sql
POST /admin/update-anything
```

## 8. Practice

```text
POST /api/v1/spaces/{spaceId}/practice-sessions
GET  /api/v1/spaces/{spaceId}/practice-sessions/{id}
POST /api/v1/spaces/{spaceId}/practice-sessions/{id}/answers
POST /api/v1/spaces/{spaceId}/practice-sessions/{id}/finish
```

答案判定由服务端完成。

## 9. Exam

```text
GET  /api/v1/spaces/{spaceId}/exams
POST /api/v1/spaces/{spaceId}/exams/{examId}/attempts
GET  /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}
POST /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/answers
POST /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/submit
GET  /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/result
```

考试进行中 Response DTO 不得返回正确答案/解析等被禁止信息。

## 10. AI Tutor

```text
POST /api/v1/spaces/{spaceId}/ai/conversations
POST /api/v1/spaces/{spaceId}/ai/conversations/{id}/messages
```

服务端决定 retrieval scope。

基于资料的回答建议结构：

```json
{
  "answer": "...",
  "citations": [
    {
      "sourceDocumentId": "...",
      "sourcePageId": "...",
      "contentBlockId": "...",
      "label": "第1章 1.1.1 / P1"
    }
  ]
}
```

客户端不能通过 prompt 指示后端跨 LearningSpace 检索未授权资料。

## 11. Response

普通成功响应默认直接返回业务 DTO。

分页：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "totalItems": 0,
  "totalPages": 0
}
```

## 12. Error

基于 `ProblemDetail` / RFC7807 风格：

```json
{
  "type": "about:blank",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/...",
  "code": "VALIDATION_ERROR",
  "requestId": "...",
  "errors": []
}
```

常见稳定错误码方向：

```text
SPACE_NOT_FOUND
SPACE_ACCESS_DENIED
SOURCE_NOT_FOUND
INGESTION_NOT_READY
CONTENT_NOT_PUBLISHED
QUESTION_NOT_FOUND
EXAM_NOT_AVAILABLE
EXAM_ATTEMPT_NOT_IN_PROGRESS
EXAM_DEADLINE_EXCEEDED
VALIDATION_ERROR
AI_PROVIDER_UNAVAILABLE
```

## 13. HTTP 状态

- 200：读取/更新成功
- 201：创建成功
- 204：成功无 body
- 400：请求格式/参数错误
- 401：未认证
- 403：无权限
- 404：不存在/按安全策略不可见
- 409：状态冲突
- 422：明确业务校验失败时可使用，项目内保持一致
- 429：限流
- 500：未预期错误
- 503：依赖不可用

## 14. Validation

- Request DTO 使用 Bean Validation。
- Entity 不直接作为 API DTO。
- space relation 创建时服务端验证双方同 space。
- 文件上传验证 size/MIME/extension/解压安全。
- ZIP 防 zip-slip。

## 15. Auth

Access：

```http
Authorization: Bearer <jwt>
```

Refresh：

- Desktop：safeStorage
- Admin Web：HttpOnly + Secure + SameSite Cookie
- Server：只存 hash

Refresh rotation / revoke 按 ADR-026。

## 16. Date/Time

API 使用 ISO-8601，例如：

```text
2026-09-04T08:30:00.123Z
```

## 17. Request ID / Logging

每个请求保留 requestId/correlation id。

严禁日志记录：

- Authorization header
- Refresh Token
- password
- AI API Key

上传日志可以记录 hash/size，不记录敏感文件全文。

## 18. OpenAPI

OpenAPI 是 Desktop/Admin/Future Android 的 Contract。

TypeScript client 优先生成，不手工复制三套 DTO。

生成代码如何纳入 repo 在 Spike 中固定。

## 19. CORS

Local 只放行明确开发 origin。

Server 只允许正式 Admin origin 等必要来源。

不得 `* + credentials`。

Electron origin/custom protocol 在 Spike 验证。
