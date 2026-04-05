# Music Share 使用与部署

本仓库当前只支持 Cloudflare 架构：

- `backend/` 部署到 Cloudflare Workers
- `web-player/` 部署到 Cloudflare Pages
- `D1` 保存分享和会话数据
- `R2` 保存音频与封面对象

不再保留旧的单机部署链路。

## 仓库结构

- `android-app/` Android 客户端
- `backend/` Worker 后端
- `web-player/` 公开试听前端

## 日常使用

### 本地开发

后端：

```bash
cd backend
npm install
npm run typecheck
npm test
npm run dev
```

前端：

```bash
cd web-player
npm install
cp .env.example .env.local
npm run dev
```

前端本地联调时，`.env.local` 至少需要：

```env
VITE_API_BASE_URL=http://localhost:8787/
```

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

### 本地联调顺序

1. 先启动 `backend` 的 `wrangler dev`
2. 再启动 `web-player` 的 Vite 开发服务器
3. 确认页面能通过 `VITE_API_BASE_URL` 访问到本地 Worker

## 部署架构

推荐域名拆分：

- `api.example.com` 对应 Worker
- `share.example.com` 或 `music.example.com` 对应 Pages

后端需要提供公开地址：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL=https://api.example.com`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL=https://share.example.com`

前端需要提供公开地址：

- `VITE_API_BASE_URL=https://api.example.com/`

## Cloudflare 部署步骤

### 1. 创建 D1 和 R2

先在 Cloudflare 中准备：

- 一个 D1 数据库
- 一个 R2 bucket

然后把真实值填入 [backend/wrangler.toml](/Users/brilliant/repo/Music%20Share_Worker/backend/wrangler.toml)：

- `[[d1_databases]].database_id`
- `[[d1_databases]].database_name`
- `[[r2_buckets]].bucket_name`

### 2. 配置 Worker 密钥和变量

必须配置的密钥：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

不要把这两个值写进 `wrangler.toml` 或提交到仓库。

最简单的做法是直接写入 Cloudflare Worker secret：

```bash
cd backend
npx wrangler secret put MUSIC_SHARE_USER_PASSWORD
npx wrangler secret put MUSIC_SHARE_ADMIN_PASSWORD
```

公开地址建议同时写入 Worker 环境变量：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

`wrangler.toml` 中已经内置了这些默认变量：

- `MUSIC_SHARE_SESSION_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_MAX_TTL_SECONDS`
- `MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_DURATION_MS`
- `MUSIC_SHARE_SESSION_COOKIE_NAME`

### 2.1 用 GitHub Actions secrets 管理更合适

如果你通过 GitHub Actions 自动部署，推荐这样分层：

- GitHub Actions Secrets：
  - `CLOUDFLARE_API_TOKEN`
  - `CLOUDFLARE_ACCOUNT_ID`
  - `MUSIC_SHARE_USER_PASSWORD`
  - `MUSIC_SHARE_ADMIN_PASSWORD`
- GitHub Actions Variables：
  - `MUSIC_SHARE_PUBLIC_API_BASE_URL`
  - `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

仓库里已经补了 workflow：

- [.github/workflows/deploy-backend.yml](/Users/brilliant/repo/Music%20Share_Worker/.github/workflows/deploy-backend.yml)

当前触发方式：

- push 到 `main`
- push 到 `cloud`
- 手动 `workflow_dispatch`

推荐原则：

- 真正的密钥和密码放 `Secrets`
- 公开域名、bucket 名、环境名这类非敏感值放 `Variables` 或 `wrangler.toml`

这个 workflow 会自动做这些事：

1. `npm ci`
2. `npm run typecheck`
3. `npm test`
4. `wrangler d1 migrations apply MUSIC_SHARE_DB --yes`
5. `wrangler deploy`
6. 把 GitHub Secrets / Variables 同步成 Worker 运行时 secret

启用前至少要在 GitHub 仓库设置中补齐：

- Repository Secrets:
  - `CLOUDFLARE_API_TOKEN`
  - `CLOUDFLARE_ACCOUNT_ID`
  - `MUSIC_SHARE_USER_PASSWORD`
  - `MUSIC_SHARE_ADMIN_PASSWORD`
- Repository Variables:
  - `MUSIC_SHARE_PUBLIC_API_BASE_URL`
  - `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

### 3. 执行 D1 迁移

本地开发数据库：

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

远端正式环境：

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB
```

### 4. 部署 Worker

```bash
cd backend
npm install
npm run deploy
```

部署完成后，优先验证这些接口：

- `POST /auth/login`
- `POST /upload`
- `GET /track/{share_code}`
- `GET /stream/{share_code}`
- `GET /cover/{share_code}`

## 后端部署细节

这一节只展开 `backend/` 的上线步骤，便于和前端发布顺序配合。

### 1. 安装依赖

```bash
cd backend
npm install
```

### 2. 准备 Cloudflare 资源

至少需要：

- 一个 D1 数据库
- 一个 R2 bucket

然后把真实值写入 [backend/wrangler.toml](/Users/brilliant/repo/Music%20Share_Worker/backend/wrangler.toml)：

- `[[d1_databases]].database_id`
- `[[d1_databases]].database_name`
- `[[r2_buckets]].bucket_name`

### 3. 配置 Worker 密钥

必须配置：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

建议同时配置公开地址：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

密码不要明文写在仓库里。推荐优先级：

1. GitHub Actions Secrets 或其他 CI secret manager
2. Cloudflare Worker secrets
3. 本地人工执行 `wrangler secret put`

如果你已经启用了上面的 GitHub Actions workflow，这两个密码和公开地址会由 workflow 自动同步到 Worker，不需要再手工执行。

手工方式示例：

```bash
cd backend
npx wrangler secret put MUSIC_SHARE_USER_PASSWORD
npx wrangler secret put MUSIC_SHARE_ADMIN_PASSWORD
npx wrangler secret put MUSIC_SHARE_PUBLIC_API_BASE_URL
npx wrangler secret put MUSIC_SHARE_PUBLIC_SHARE_BASE_URL
```

### 4. 执行 D1 迁移

本地开发库：

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

正式环境：

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB
```

### 5. 发布前校验

```bash
cd backend
npm run typecheck
npm test
```

### 6. 部署后端

```bash
cd backend
npm run deploy
```

### 7. 后端上线后检查

至少检查：

- Worker 自定义域名是否已生效
- D1 查询是否正常
- R2 上传与读取是否正常
- Cron Trigger 是否存在
- `track_url`、`stream_url`、`cover_url`、`share_url` 是否返回正确域名

## 前端部署细节

这一节展开 `web-player/` 的上线步骤，并和后端部署顺序配合。

### 1. 配置 Pages 环境变量

Pages 推荐配置：

- Framework preset: `None`
- Build command: `npm run build`
- Build output directory: `dist`

Pages 环境变量至少需要：

```env
VITE_API_BASE_URL=https://api.example.com/
```

如果通过 GitHub Actions 自动部署 Pages，仓库里已经补了：

- [.github/workflows/deploy-web-player.yml](/Users/brilliant/repo/Music%20Share_Worker/.github/workflows/deploy-web-player.yml)

这个 workflow 的必需配置是：

- Repository Secrets:
  - `CLOUDFLARE_API_TOKEN`
  - `CLOUDFLARE_ACCOUNT_ID`
- Repository Variables:
  - `CLOUDFLARE_PAGES_PROJECT_NAME`
  - `VITE_API_BASE_URL`

当前触发方式：

- push 到 `main`
- push 到 `cloud`
- 面向 `main/cloud` 的 pull request 会生成 Pages preview
- 手动 `workflow_dispatch`

注意：

- `CLOUDFLARE_PAGES_PROJECT_NAME` 必须是已经创建好的 Pages 项目名
- Pages 项目的 production branch 应与你实际用来发正式版的分支一致，例如 `main` 或 `cloud`

### 2. 构建前端

构建前端：

```bash
cd web-player
npm install
npm run build
```

`web-player/public/_redirects` 已经提供了 SPA fallback，部署时应随 `dist/` 一起发布。

### 3. 验证分享页

部署完成后至少验证：

- 直接打开 `/{shareCode}` 能进入页面
- 刷新 `/{shareCode}` 不会返回静态 404
- `GET /track/{share_code}` 返回 `200` 时页面能完成音频下载并播放
- `GET /track/{share_code}` 返回 `404` 时进入前端 Not Found 页面
- `GET /track/{share_code}` 返回 `410` 时进入前端 Expired 页面
- `stream_url` 或 `cover_url` 异常时页面显示错误，不误判为分享不存在

## 部署顺序建议

结合前端说明，推荐按这个顺序部署：

1. 先准备后端依赖资源：D1、R2、Worker secrets、公开域名。
2. 执行后端 D1 迁移并部署 Worker。
3. 用真实 API 域名先验证 `login`、`upload`、`track`、`stream`、`cover`。
4. 后端稳定后，先在 GitHub 仓库配置 Pages workflow 所需的 `CLOUDFLARE_PAGES_PROJECT_NAME` 和 `VITE_API_BASE_URL`。
5. 再部署 Web Player 到 Pages，确保 `VITE_API_BASE_URL` 指向正式 API 域名。
6. 最后用真实分享链接做端到端验证，确认分享页、音频下载、封面读取、404/410 页面都正常。

## 进一步说明

详细模块说明见：

- [README.md](/Users/brilliant/repo/Music%20Share_Worker/README.md)
- [backend/README.md](/Users/brilliant/repo/Music%20Share_Worker/backend/README.md)
- [web-player/README.md](/Users/brilliant/repo/Music%20Share_Worker/web-player/README.md)
