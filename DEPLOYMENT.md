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

可以通过 Wrangler 写入：

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

示例：

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
4. 后端稳定后，再部署 Web Player 到 Pages，并把 `VITE_API_BASE_URL` 指向正式 API 域名。
5. 最后用真实分享链接做端到端验证，确认分享页、音频下载、封面读取、404/410 页面都正常。

## 进一步说明

详细模块说明见：

- [README.md](/Users/brilliant/repo/Music%20Share_Worker/README.md)
- [backend/README.md](/Users/brilliant/repo/Music%20Share_Worker/backend/README.md)
- [web-player/README.md](/Users/brilliant/repo/Music%20Share_Worker/web-player/README.md)
