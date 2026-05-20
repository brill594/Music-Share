# Music Share 部署说明

本仓库支持两种部署形态：

1. Cloudflare 单 Worker 部署：`worker-backend/` 使用 Workers + D1 + R2 + Workers Assets，同一个 Worker 同时承载 API 和 Web Player。
2. 裸金属同源部署：`backend/` 使用 FastAPI + SQLite + 本地文件系统 + Nginx，根目录 `install.sh` 一键安装。

两种后端实现保持 Android App 和 Web Player 使用的 API 路径与主要响应字段兼容。

## 从旧 Worker-only 版本迁移

旧版本把 Cloudflare Worker 后端直接放在 `backend/`，并通常另外使用 Pages/GitHub Pages 承载前端。新版本把 Worker 后端移动到 `worker-backend/`，并由同一个 Worker 通过 Workers Assets 承载前端。

新布局：

- `worker-backend/`：Cloudflare Worker 后端，继续使用 Workers + D1 + R2。
- `web-player/`：构建后作为 Worker assets 绑定到同一个 Worker。
- `backend/`：裸金属 FastAPI 后端，只用于 Linux 服务器部署；继续使用 Cloudflare 时不要在这里执行 Wrangler。

如果你只是从老版本 Worker-only 升级到新版本并继续使用 Cloudflare：

1. 拉取新代码后，不要再进入 `backend/` 执行 Wrangler 命令；改用 `worker-backend/`。
2. 把旧 `backend/wrangler.toml` 中的 D1/R2 生产绑定复制到 `worker-backend/wrangler.toml`：
   - `[[d1_databases]].database_name`
   - `[[d1_databases]].database_id`
   - `[[r2_buckets]].bucket_name`
3. 确认 `worker-backend/wrangler.toml` 包含 Workers Assets 绑定：

   ```toml
   [assets]
   directory = "../web-player/dist"
   binding = "ASSETS"
   not_found_handling = "single-page-application"
   run_worker_first = true
   ```

4. GitHub Secrets 保持：
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
   - `MUSIC_SHARE_USER_PASSWORD`
   - `MUSIC_SHARE_ADMIN_PASSWORD`
5. GitHub Variables 调整为只保留 Worker 需要的公开地址：
   - `MUSIC_SHARE_PUBLIC_API_BASE_URL`
   - `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

   单 Worker 部署时，这两个值应都指向同一个 Worker 公开域名，例如：

   ```text
   MUSIC_SHARE_PUBLIC_API_BASE_URL=https://music.example.com
   MUSIC_SHARE_PUBLIC_SHARE_BASE_URL=https://music.example.com
   ```

   不再需要 `CLOUDFLARE_PAGES_PROJECT_NAME` 和 `VITE_API_BASE_URL`。Web Player 会使用同源 API。

6. 本地验证 Worker：

   ```bash
   cd worker-backend
   npm install
   npm run typecheck
   npm test
   ```

7. 本地验证 Web Player 构建：

   ```bash
   cd web-player
   npm install
   npm run build
   ```

8. 部署单 Worker：

   ```bash
   cd worker-backend
   npx wrangler d1 migrations apply MUSIC_SHARE_DB --remote
   npm run deploy
   ```

9. GitHub Actions 只需要运行 `Deploy Worker Backend`。这个 workflow 会构建 `web-player/`，再部署同一个 Worker。

继续使用 Worker 时，不需要迁移 D1/R2 数据；只要 `worker-backend/wrangler.toml` 指向原来的 D1 database 和 R2 bucket，已有分享、会话、对象仍由原 Cloudflare 资源承载。

旧的 Pages/GitHub Pages 可以删除或关闭；前端已经由 Worker assets 承载。删除前先确认 Worker 域名打开 `/<share_code>` 能显示播放页。

如果你要从 Worker-only 切换到裸金属：

1. 先按“形态二：裸金属部署”安装并验证新服务器。
2. 新裸金属后端使用 SQLite 和本地文件系统，不能直接读取 D1/R2；当前仓库没有自动 D1/R2 → SQLite/本地文件迁移脚本。
3. 不要在验证前把 Android 或公开域名切到裸金属。先用测试域名完成登录、上传、播放、管理后台检查。
4. 确认新部署可用后，再把 Android 后端地址、Web Player 公开入口或 DNS 切到裸金属站点。
5. 旧 Worker/D1/R2 可以保留为回滚路径；确认不再需要旧分享后再清理 Cloudflare 资源。

## 形态一：Cloudflare 单 Worker 部署

Worker 部署使用：

- `worker-backend/`：Cloudflare Worker API 源码
- `worker-backend/wrangler.toml`：Worker、D1、R2、Cron、Workers Assets 配置
- `worker-backend/migrations/`：D1 schema migrations
- `web-player/`：Worker assets 的源代码，部署前构建为 `web-player/dist`
- `.github/workflows/deploy-worker.yml`：构建前端、执行远端 D1 migrations、部署 Worker

### Cloudflare 准备

至少需要：

- Cloudflare 账号
- D1 数据库
- R2 bucket
- 一个 Worker 公开域名，例如 `https://music.example.com`

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

单 Worker 部署时，两者都设置为 Worker 域名。

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

### 前端构建

```bash
cd web-player
npm install
npm run build
```

不要为单 Worker 生产构建设置 `VITE_API_BASE_URL`；未设置时 Web Player 使用同源 API。

## 形态二：裸金属部署

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

Cloudflare 单 Worker 和裸金属都使用同源 Web Player。只有本地开发或外部前端单独部署时才需要 `VITE_API_BASE_URL`。
