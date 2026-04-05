# Music Share Backend

Music Share 后端已收敛为纯 Cloudflare 运行时实现：

- `Workers` 负责路由、鉴权、响应序列化
- `D1` 保存 `shares` 和 `sessions`
- `R2` 保存音频与封面对象
- `Cron Triggers` 负责清理已过期或已终止的分享

这个分支不再保留旧的 `FastAPI + SQLite + 本地文件系统 + Nginx` 实现。

## 目录结构

- `src/` Worker 源码
- `migrations/` D1 schema
- `wrangler.toml` Cloudflare 绑定与 Cron 配置
- `package.json` Worker 本地开发、测试和部署脚本

## 接口兼容性

为保证 Android App 和 Web Player 兼容，接口路径与主要字段名保持不变：

- `POST /auth/login`
- `POST /upload`
- `GET /track/{share_code}`
- `GET /stream/{share_code}`
- `GET /cover/{share_code}`
- `GET /client/shares`
- `GET /client/shares/{share_code}`
- `POST /client/shares/{share_code}/terminate`
- `GET /admin/tracks`
- `POST /admin/tracks/{share_code}/terminate`

接口返回中继续保留这些关键字段：

- `session_key`
- `share_code`
- `share_url`
- `track_url`
- `stream_url`
- `cover_url`
- `status`
- `client_created_at`
- `terminated_at`
- `remaining_seconds`
- `client_install_id`

## 本地开发

### 1. 安装依赖

```bash
cd backend
npm install
```

### 2. 配置必要变量

至少需要：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

同时需要在 `wrangler.toml` 中绑定：

- `MUSIC_SHARE_DB`
- `MUSIC_SHARE_BUCKET`

可选变量继续沿用旧命名：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `MUSIC_SHARE_SESSION_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_MAX_TTL_SECONDS`
- `MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_DURATION_MS`
- `MUSIC_SHARE_SESSION_COOKIE_NAME`

安全建议：

- 不要把 `MUSIC_SHARE_USER_PASSWORD` 或 `MUSIC_SHARE_ADMIN_PASSWORD` 明文写进仓库
- 这两个值应通过 Cloudflare Worker secret 或 CI secret manager 注入
- `MUSIC_SHARE_PUBLIC_API_BASE_URL`、`MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 这类公开地址不是敏感信息，可以放 `vars`
- 如果使用 GitHub Actions 自动部署，优先放到仓库 `Secrets/Variables`，由 workflow 同步到 Worker

### 3. 初始化 D1

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

部署前再对远端环境执行一次正式迁移。

### 4. 本地运行

```bash
cd backend
npm run dev
```

### 5. 验证

```bash
cd backend
npm run typecheck
npm test
```

## 存储约定

首阶段保持与旧字段语义兼容：

- `audio_path` 现在保存的是 R2 object key
- `cover_path` 现在保存的是 R2 object key
- `status` 字段继续保留，以降低客户端改动风险

当前对象键格式：

- `shares/{uuid}/audio.<ext>`
- `shares/{uuid}/cover.<ext>`

## 清理模型

Cron 每分钟执行一次清理：

1. 查询 D1 中已过期或已终止的分享
2. 删除对应 R2 对象
3. 删除 D1 中的分享记录
4. 删除过期 session

## 部署

```bash
cd backend
npm run deploy
```

部署前请先把 `backend/wrangler.toml` 中的 `database_id`、bucket 名称和环境变量配置为真实值。
