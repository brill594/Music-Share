# Music Share Worker Backend

`worker-backend/` 是 Music Share 的 Cloudflare 单 Worker 部署实现。它和 `web-player/` 合并部署：同一个 Worker 同时承载 API、D1/R2 存储访问、公开播放页静态资源。

当前 Worker 部署基于：

- Cloudflare Workers：路由、鉴权、响应序列化
- Workers Assets：承载 `web-player/dist`
- D1：保存 `shares`、`sessions` 和配置
- R2：保存音频、封面和背景图对象
- Cron Triggers：清理过期或终止的分享

## 目录结构

- `src/` Worker API 源码
- `migrations/` D1 schema
- `wrangler.toml` Worker、Assets、D1、R2、Cron 配置
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

其他非 API 路径交给 Workers Assets，例如 `/`、`/<share_code>`、`/assets/...`。

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

- `ASSETS`
- `MUSIC_SHARE_DB`
- `MUSIC_SHARE_BUCKET`

Assets 绑定必须指向前端构建目录：

```toml
[assets]
directory = "../web-player/dist"
binding = "ASSETS"
not_found_handling = "single-page-application"
run_worker_first = true
```

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

单 Worker 生产部署时，`MUSIC_SHARE_PUBLIC_API_BASE_URL` 和 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 应设置为同一个 Worker 公开域名。

不要把 `MUSIC_SHARE_USER_PASSWORD` 或 `MUSIC_SHARE_ADMIN_PASSWORD` 明文写进仓库；这两个值应通过 Cloudflare Worker secret 或 CI secret manager 注入。

初始化本地 D1：

```bash
cd worker-backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

构建前端 assets：

```bash
cd ../web-player
npm install
npm run build
```

运行 Worker：

```bash
cd ../worker-backend
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

- `.github/workflows/deploy-worker.yml` 构建 `web-player/`，部署 `worker-backend/`，最终只有一个 Worker 服务 API + 前端。

不再需要 Cloudflare Pages / GitHub Pages，也不再需要 `.github/workflows/deploy-web-player.yml`。

手动部署：

```bash
cd web-player
npm install
npm run build

cd ../worker-backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --remote
npm run deploy
```

不要为单 Worker 生产构建设置 `VITE_API_BASE_URL`；Web Player 会使用同源 API。

## 存储约定

对象键格式：

- `shares/{uuid}/audio.<ext>`
- `shares/{uuid}/cover.<ext>`
- 全局背景图对象由 Worker 实现管理

`share_url`、`track_url`、`stream_url`、`cover_url`、`background_url` 会根据 `MUSIC_SHARE_PUBLIC_API_BASE_URL` 和 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 生成。单 Worker 部署时，两者应指向同一个 Worker 域名。
