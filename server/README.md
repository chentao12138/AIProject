# SPIKE-001 · Spring Boot + Java 21 + Maven Wrapper 最小启动验证

本目录是 AIStudy Backend 的 Spring Boot 工程。当前仅用于 **SPIKE-001 技术链路验证**：
Java 21 + Spring Boot 3.5.x + Maven Wrapper 3.9.x + Windows 环境能否形成一个可运行、可测试、可打包的 Backend。

不包含任何业务功能。后续 Spike / Vertical Slice 阶段将在此工程上继续扩展。

## 技术栈（SPIKE-001 已验证）

| 层 | 版本 |
|---|---|
| Java | 21 (Temurin 21.0.11) |
| Spring Boot | 3.5.x |
| Maven (Wrapper) | 3.9.x（Windows 全局 Maven 3.6.2 不修改） |
| Packaging | jar |
| 绑定地址 | 127.0.0.1:8080（本地仅） |

## 快速命令

```powershell
cd D:\AIProject\server

# 版本检查
.\mvnw.cmd -version

# 单元测试
.\mvnw.cmd test

# 打包
.\mvnw.cmd package

# 启动
.\mvnw.cmd spring-boot:run
# 或
java -jar target\server-0.1.0-SPIKE.jar

# 验证
curl http://127.0.0.1:8080/health
```

## 当前端点

- `GET /health` — SPIKE-001 最小健康检查（非业务端点）

## 后续

SPIKE-001 通过后：SPIKE-002 将引入 MySQL + MyBatis-Plus；SPIKE-003 Flyway；SPIKE-004 Auth + Space Authorization；SPIKE-005 OpenAPI；SPIKE-006 Electron File Upload；...
