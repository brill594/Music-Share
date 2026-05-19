# Music Share Worker Backend

`worker-backend/` 是 Music Share 的 Cloudflare Worker 后端实现，用于和 `backend/` 裸金属 FastAPI 后端并存。两者面向 Android App 和 Web Player 暴露相同的主要 API 契约。

当前 Worker 部署基于：

- Cloudflare Workers：路由、鉴权、响应序列化
- D1：保存 `shares`、`sessions` 和配置
- R2：保存音频、封面和背景图对象
- Cron Triggers：清理过期或终止的分享

## 目录结构

- `src/` Worker 源码
- `migrations/` D1 schema
- `wrangler.toml` Cloudflare 绑定与 Cron 配置
- `package.json` 本地开发、测试和部署脚本

## 接口兼容性

Worker 后端应保持与裸金属后端兼容的主要接口：

- `POST /auth/login`
- `POST /upload`
- `GET /track/{share_code}`
- `GET /stream/{share_code}`
- `GET /cover/{share_code}`
- `GET /background`
- `GET /client/shares`
- `GET /client/shares/{share_code}`
- `POST /client/shares/{share_code}/terminate`
- `GET /admin/tracks`
- `POST /admin/tracks/{share_code}/terminate`
- `GET /admin/background`
- `POST /admin/background`
- `GET /admin/usage`
- `POST /admin/usage`

## 本地开发

安装依赖：

```bash
cd worker-backend
npm install
```

至少需要配置：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

同时需要在 `wrangler.toml` 中绑定：

- `MUSIC_SHARE_DB`
- `MUSIC_SHARE_BUCKET`

可选变量继续沿用同名配置：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `MUSIC_SHARE_SESSION_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS`
- `MUSIC_SHARE_SHARE_MAX_TTL_SECONDS`
- `MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES`
- `MUSIC_SHARE_MAX_DURATION_MS`
- `MUSIC_SHARE_SESSION_COOKIE_NAME`

不要把 `MUSIC_SHARE_USER_PASSWORD` 或 `MUSIC_SHARE_ADMIN_PASSWORD` 明文写进仓库；这两个值应通过 Cloudflare Worker secret 或 CI secret manager 注入。

初始化本地 D1：

```bash
cd worker-backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

运行：

```bash
cd worker-backend
npm run dev
```

验证：

```bash
cd worker-backend
npm run typecheck
npm test
```

## 部署

GitHub Actions 路径：

- `.github/workflows/deploy-worker.yml` 部署 `worker-backend/`
- `.github/workflows/deploy-web-player.yml` 部署 `web-player/` 到 Cloudflare Pages

手动部署：

```bash
cd worker-backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --remote
npm run deploy
```

Web Player 部署到 Cloudflare Pages 时必须配置：

```text
VITE_API_BASE_URL=https://api.example.com/
```

## 存储约定

对象键格式：

- `shares/{uuid}/audio.<ext>`
- `shares/{uuid}/cover.<ext>`
- 全局背景图对象由 Worker 实现管理

`share_url`、`track_url`、`stream_url`、`cover_url`、`background_url` 会根据 `MUSIC_SHARE_PUBLIC_API_BASE_URL` 和 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 生成。
