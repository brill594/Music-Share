# Music Share 裸金属部署说明

当前部署目标是单台 Linux 服务器：

- `backend/`：FastAPI API 服务，使用 SQLite 保存元数据和会话
- `backend/data/storage/`：本地文件系统保存音频、封面、全局背景图
- `web-player/dist`：Vite 构建后的公开试听前端
- `nginx`：同源提供前端静态文件，反向代理 API，并通过 `X-Accel-Redirect` 受控分发音频
- `systemd`：管理后端进程
- `certbot`：申请和续期 HTTPS 证书

Cloudflare Worker / Pages / D1 / R2 部署文件已移除，生产路径以根目录脚本为准。

## 部署前准备

至少需要：

- 一台 Linux 服务器
- 一个已经解析到这台服务器的域名，或一个公网 IPv4 地址
- 可用的 80 端口用于 HTTP-01 校验
- 可用的 HTTPS 对外端口，默认 443
- `root` 或 `sudo` 权限

如果用域名申请 Let’s Encrypt 证书，还需要一个 Certbot 邮箱。

## 一键安装

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

安装过程中会提示输入域名/IP、HTTPS 端口和证书邮箱。

## Nginx 路由

生成的 Nginx 配置使用同一个站点合并前端和后端：

- `/assets/` 和其他静态路径从 `web-player/dist` 读取。
- `/` 回退到 `index.html`，供 Web Player 的前端路由使用。
- `/auth`、`/upload`、`/client`、`/admin`、`/track`、`/stream`、`/cover`、`/background`、`/docs`、`/redoc` 代理到 FastAPI。
- `/internal-media/` 是 Nginx internal alias，只允许后端通过 `X-Accel-Redirect` 触发读取。

因此 Web Player 默认不需要 `VITE_API_BASE_URL`；未配置时会访问当前页面同源 API。

## 服务管理

部署完成后使用根目录脚本：

```bash
sudo bash ./start.sh start
sudo bash ./start.sh restart
sudo bash ./start.sh status
sudo bash ./start.sh logs
sudo bash ./start.sh stop
```

默认服务名是 `music-share-backend.service`。可通过环境变量 `MUSIC_SHARE_SYSTEMD_SERVICE_NAME` 覆盖。

## 运行时数据

默认数据目录是：

```text
/var/lib/music-share
```

通常包含：

- `music_share.sqlite3`
- `runtime-secrets.json`
- `usage-limits.json`
- `storage/<uuid>/audio.*`
- `storage/<uuid>/cover.*`
- `storage/<uuid>/meta.json`
- `storage/global-background/background.*`
- `storage/global-background.json`

如果没有显式配置 `MUSIC_SHARE_USER_PASSWORD` 和 `MUSIC_SHARE_ADMIN_PASSWORD`，后端首次启动会自动生成缺失密码并写入 `runtime-secrets.json`，后续启动会复用该文件。生产环境建议把密码固定到 `backend/.env` 或其他受控环境变量中；环境变量提供的密码不会被回写到运行时密钥文件。

## 常用环境变量

部署脚本和后端常用变量：

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

## 手动后端开发启动

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.asgi:app --host 127.0.0.1 --port 2087
```

## 手动前端构建

```bash
cd web-player
npm install
npm run build
```

## 架构限制

- 后端依赖 SQLite 和本地文件系统，不是无状态服务。
- 音频分发默认依赖 Nginx `X-Accel-Redirect`。
- 更适合同机部署前端静态资源与后端 API。
- IP 地址证书依赖 Certbot 对当前环境的支持。
