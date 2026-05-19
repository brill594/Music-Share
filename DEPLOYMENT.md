# Music Share 部署说明

本仓库同时支持两种部署形态：

1. 裸金属同源部署：`backend/` FastAPI + SQLite + 本地文件系统 + Nginx，根目录 `install.sh` 一键安装。
2. Cloudflare Worker 部署：`worker-backend/` Workers + D1 + R2，`web-player/` 可部署到 Cloudflare Pages 并通过 `VITE_API_BASE_URL` 访问 Worker API。

两种后端实现保持 Android App 和 Web Player 使用的 API 路径与主要响应字段兼容。

## 形态一：裸金属部署

目标是单台 Linux 服务器：

- `backend/`：FastAPI API 服务，使用 SQLite 保存元数据和会话
- `backend/data/storage/`：本地文件系统保存音频、封面、全局背景图
- `web-player/dist`：Vite 构建后的公开试听前端
- `nginx`：同源提供前端静态文件，反向代理 API，并通过 `X-Accel-Redirect` 受控分发音频
- `systemd`：管理后端进程
- `certbot`：申请和续期 HTTPS 证书

### 裸金属准备

至少需要：

- 一台 Linux 服务器
- 一个已经解析到这台服务器的域名，或一个公网 IPv4 地址
- 可用的 80 端口用于 HTTP-01 校验
- 可用的 HTTPS 对外端口，默认 443
- `root` 或 `sudo` 权限

### 裸金属一键安装

在仓库根目录执行：

```bash
sudo bash ./install.sh
```

安装脚本会：

1. 检查系统并安装基础依赖。
2. 为 `backend/` 创建 `.venv` 并执行 `pip install -e "${BACKEND_DIR}"`。
3. 在 `web-player/` 中安装 npm 依赖并执行 `npm run build`。
4. 创建默认系统用户 `music-share`。
5. 准备默认运行时数据目录 `/var/lib/music-share`。
6. 调用 `backend/setup.sh` 生成 Nginx 配置、申请证书、写入 `backend/.env`。
7. 安装 `/etc/systemd/system/music-share-backend.service`。
8. 安装并启用 Certbot 续期 timer。

### 裸金属 Nginx 路由

生成的 Nginx 配置使用同一个站点合并前端和后端：

- `/assets/` 和其他静态路径从 `web-player/dist` 读取。
- `/` 回退到 `index.html`，供 Web Player 的前端路由使用。
- `/auth`、`/upload`、`/client`、`/admin`、`/track`、`/stream`、`/cover`、`/background`、`/docs`、`/redoc` 代理到 FastAPI。
- `/internal-media/` 是 Nginx internal alias，只允许后端通过 `X-Accel-Redirect` 触发读取。

因此裸金属 Web Player 默认不需要 `VITE_API_BASE_URL`；未配置时会访问当前页面同源 API。

### 裸金属服务管理

```bash
sudo bash ./start.sh start
sudo bash ./start.sh restart
sudo bash ./start.sh status
sudo bash ./start.sh logs
sudo bash ./start.sh stop
```

默认服务名是 `music-share-backend.service`。可通过 `MUSIC_SHARE_SYSTEMD_SERVICE_NAME` 覆盖。

### 裸金属运行时数据

默认数据目录是 `/var/lib/music-share`，通常包含：

- `music_share.sqlite3`
- `runtime-secrets.json`
- `usage-limits.json`
- `storage/<uuid>/audio.*`
- `storage/<uuid>/cover.*`
- `storage/<uuid>/meta.json`
- `storage/global-background/background-*`
- `storage/global-background.json`

如果没有显式配置 `MUSIC_SHARE_USER_PASSWORD` 和 `MUSIC_SHARE_ADMIN_PASSWORD`，后端首次启动会自动生成缺失密码并写入 `runtime-secrets.json`，后续启动会复用该文件。生产环境建议把密码固定到 `backend/.env` 或其他受控环境变量中；环境变量提供的密码不会被回写到运行时密钥文件。

常用裸金属环境变量：

- `MUSIC_SHARE_PUBLIC_HTTPS_PORT`
- `MUSIC_SHARE_BACKEND_HOST`
- `MUSIC_SHARE_BACKEND_PORT`
- `MUSIC_SHARE_BIND_HOST`
- `MUSIC_SHARE_BIND_PORT`
- `MUSIC_SHARE_DATA_ROOT`
- `MUSIC_SHARE_SERVICE_DATA_ROOT`
- `MUSIC_SHARE_SERVICE_USER`
- `MUSIC_SHARE_SYSTEMD_SERVICE_NAME`
- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`
- `MUSIC_SHARE_CORS_ALLOWED_ORIGINS`

证书相关：

- `MUSIC_SHARE_CERTBOT_MODE=dns-cloudflare`
- `MUSIC_SHARE_CLOUDFLARE_API_TOKEN`

## 形态二：Cloudflare Worker + Pages 部署

Worker 部署使用：

- `worker-backend/`：Cloudflare Worker API 源码
- `worker-backend/wrangler.toml`：Worker、D1、R2、Cron 配置
- `worker-backend/migrations/`：D1 schema migrations
- `web-player/`：Cloudflare Pages 前端
- `.github/workflows/deploy-worker.yml`：部署 Worker 并执行远端 D1 migrations
- `.github/workflows/deploy-web-player.yml`：构建并部署 Web Player 到 Pages

### Cloudflare 准备

至少需要：

- Cloudflare 账号
- D1 数据库
- R2 bucket
- Cloudflare Pages 项目
- Worker API 域名，例如 `https://api.example.com`
- Pages 前端域名，例如 `https://share.example.com`

`worker-backend/wrangler.toml` 中需要配置：

- `[[d1_databases]].database_id`
- `[[d1_databases]].database_name`
- `[[r2_buckets]].bucket_name`

这些绑定 ID 可以进入仓库；密码和 token 不应进入仓库。

### GitHub Actions 配置

Repository Secrets：

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

Repository Variables：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `CLOUDFLARE_PAGES_PROJECT_NAME`
- `VITE_API_BASE_URL`

其中 `VITE_API_BASE_URL` 必须指向 Worker API，例如：

```text
VITE_API_BASE_URL=https://api.example.com/
```

### Worker 本地开发与验证

```bash
cd worker-backend
npm install
npm run typecheck
npm test
npm run dev
```

部署前可手动应用 migrations：

```bash
cd worker-backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --remote
npm run deploy
```

### Pages 前端构建

```bash
cd web-player
npm install
VITE_API_BASE_URL=https://api.example.com/ npm run build
```

## 共享兼容要求

两种部署形态都应保持这些客户端契约：

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

`web-player` 未配置 `VITE_API_BASE_URL` 时使用同源 API，适合裸金属；配置后使用指定绝对 URL，适合 Worker + Pages。
