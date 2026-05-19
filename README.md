# Music Share

Music Share 是一套临时音频分享工具，包含：

- Android 客户端：读取当前播放曲目、上传音频/封面/元数据、管理自己的分享
- 后端服务：鉴权、会话管理、分享查询、音频分发、过期清理
- Web Player：公开试听页，按分享码展示歌曲信息并播放音频

当前仓库同时支持两种部署形态：

- 裸金属：`backend/` 使用 `FastAPI + SQLite + 本地文件系统 + Nginx`，根目录 `install.sh` 会构建 `web-player/dist` 并同源部署前后端。
- Cloudflare Worker：`worker-backend/` 使用 `Workers + D1 + R2`，`web-player/` 可通过 `VITE_API_BASE_URL` 部署到 Cloudflare Pages 并访问 Worker API。

## 仓库结构

- `android-app/` Android 客户端
- `backend/` 裸金属 FastAPI 后端、SQLite/本地文件存储、Nginx/Certbot 配置脚本
- `worker-backend/` Cloudflare Worker 后端、D1 migrations、Wrangler 配置
- `web-player/` Vue 3 + Vite 公开试听前端
- `install.sh` 裸金属 Linux 服务器一键安装脚本，会安装 `backend/` 并构建前端
- `start.sh` 裸金属已部署 `systemd` 服务的启动/停止/查看日志脚本

## 核心能力

- 统一密码登录，自动区分普通用户与管理员
- 上传音频、封面和歌曲元数据
- 生成公开分享码和公开试听页
- 公开接口：`/track/{share_code}`、`/stream/{share_code}`、`/cover/{share_code}`、`/background`
- 客户端管理接口：查看自己的分享、查看单条分享状态、提前终止分享
- 管理员接口：查看全部分享、终止任意分享、配置全局背景图、查看/更新用量阈值
- 自动清理过期分享、终止分享和过期 session

## 本地开发

裸金属后端：

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.asgi:app --host 127.0.0.1 --port 2087
```

Worker 后端：

```bash
cd worker-backend
npm install
npm run dev
```

Web Player：

```bash
cd web-player
npm install
npm run dev
```

如果没有配置 `VITE_API_BASE_URL`，Web Player 会使用当前页面同源地址作为 API 根地址，适合裸金属同源部署。部署到 Cloudflare Pages 时应显式配置 `VITE_API_BASE_URL` 指向 Worker API。

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

## 部署

裸金属部署：

```bash
sudo bash ./install.sh
```

安装脚本会安装 `backend/`、构建 `web-player/dist`、配置 Nginx/证书、安装 `music-share-backend.service` 和证书续期 timer。

Cloudflare Worker + Pages 部署：

- `worker-backend/wrangler.toml` 配置 Worker、D1、R2 和 Cron。
- `.github/workflows/deploy-worker.yml` 部署 Worker 并应用 D1 migrations。
- `.github/workflows/deploy-web-player.yml` 构建并部署 `web-player/` 到 Cloudflare Pages。

从旧 Worker-only 版本升级时，先看 `DEPLOYMENT.md` 的“从旧 Worker-only 版本迁移”：继续使用 Cloudflare 时迁到 `worker-backend/` 并复用原 D1/R2；切换裸金属时需要新部署验证后再切流量。

完整部署说明见 `DEPLOYMENT.md`。裸金属后端细节见 `backend/README.md`，Worker 后端细节见 `worker-backend/README.md`，Web Player 细节见 `web-player/README.md`。
