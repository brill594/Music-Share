# Music Share

Music Share 是一套临时音频分享工具，包含：

- Android 客户端：选择音频、上传元数据、管理自己的分享
- 后端服务：鉴权、会话管理、分享查询、音频分发、过期清理
- Web Player：公开试听页，按分享码展示歌曲信息并播放音频

当前实现适合自用或小规模使用，后端基于 `FastAPI + SQLite + 本地文件系统 + Nginx`，公开前端基于 `Vue 3 + Vite`。

## 仓库结构

- `android-app/` Android 客户端
- `backend/` 后端 API、鉴权、数据清理、部署脚本
- `web-player/` 公开试听前端
- `install.sh` Linux 服务器一键安装脚本
- `start.sh` 已部署服务的启动/停止/查看日志脚本

更细的模块说明见：

- `backend/README.md`
- `web-player/README.md`

## 功能概览

- 统一密码登录，自动区分普通用户与管理员
- 上传音频、封面和歌曲元数据
- 生成公开分享码
- 提供公开接口：
  - `GET /track/{share_code}`
  - `GET /stream/{share_code}`
  - `GET /cover/{share_code}`
- 提供客户端管理接口：
  - 查看自己创建的分享
  - 查看单条分享状态
  - 提前终止自己的分享
- 提供管理员接口：
  - 查看全部分享
  - 终止任意分享
- 自动清理过期分享、终止分享和过期 session

## 快速开始

### 本地开发

后端：

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.asgi:app --host 127.0.0.1 --port 2087
```

前端：

```bash
cd web-player
npm install
npm run dev
```

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

## 服务器部署

根目录提供了面向 Linux 服务器的部署脚本。

安装：

```bash
sudo bash ./install.sh
```

安装脚本会自动完成这些事：

- 检查并安装基础依赖
- 创建后端虚拟环境并安装 Python 依赖
- 安装前端依赖并构建 `web-player/dist`
- 创建 `music-share` 系统用户
- 准备运行数据目录，默认是 `/var/lib/music-share`
- 调用 `backend/setup.sh` 配置 Nginx、证书和公开地址
- 安装 `systemd` 服务
- 安装并启用证书自动续期 timer

安装过程中会提示输入：

- 绑定的域名或 IPv4 地址
- 对外 HTTPS 端口
- Certbot 邮箱

说明：

- `install.sh` 必须用 `root` 执行
- 后端实际只监听本机回环地址 `127.0.0.1:2087`
- 对外暴露的 HTTPS 端口由 Nginx 提供，默认 `443`，也可以改成其他端口
- 如果系统已经安装了 `certbot`，脚本会保留现有版本，不会自动升级它

## 服务管理

部署完成后通过根目录脚本管理服务：

```bash
sudo bash ./start.sh
sudo bash ./start.sh status
sudo bash ./start.sh logs
sudo bash ./start.sh stop
```

默认 `systemd` 服务名：

- `music-share-backend.service`

默认续期 timer：

- `music-share-backend-certbot-renew.timer`

当前续期检查频率是每天两次，时间为 `03:17` 和 `15:17`，并带最多 `45` 分钟随机延迟。

## 运行时数据

默认运行时数据目录：

- `/var/lib/music-share`

其中通常包含：

- `music_share.sqlite3`
- `runtime-secrets.json`
- `storage/<uuid>/audio.*`
- `storage/<uuid>/cover.*`
- `storage/<uuid>/meta.json`

如果没有显式配置以下环境变量：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

服务首次启动时会自动生成密码，并写入：

- `/var/lib/music-share/runtime-secrets.json`

生产环境建议把密码固定到 `backend/.env` 或其他受控环境变量中，不要长期依赖自动生成密码。

## 常用环境变量

部署时最常见的变量有：

- `MUSIC_SHARE_PUBLIC_HTTPS_PORT`
- `MUSIC_SHARE_BACKEND_HOST`
- `MUSIC_SHARE_BACKEND_PORT`
- `MUSIC_SHARE_BIND_HOST`
- `MUSIC_SHARE_BIND_PORT`
- `MUSIC_SHARE_DATA_ROOT`
- `MUSIC_SHARE_SERVICE_DATA_ROOT`
- `MUSIC_SHARE_SERVICE_USER`
- `MUSIC_SHARE_SYSTEMD_SERVICE_NAME`

如果你需要 Cloudflare DNS 方式申请证书，也可以使用：

- `MUSIC_SHARE_CERTBOT_MODE=dns-cloudflare`
- `MUSIC_SHARE_CLOUDFLARE_API_TOKEN`

## 当前架构限制

- 后端当前依赖 SQLite 和本地文件系统，不是无状态架构
- 音频分发默认依赖 Nginx `X-Accel-Redirect`
- 更适合同机部署前端静态资源与后端 API
- 如果使用 IP 地址证书，需要较新的 `certbot`

## 适用场景

这套实现更适合：

- 自用
- 小规模分享
- 低频访问
- 单机部署

如果后续需要迁移到 Cloudflare Workers / R2 / D1，这个仓库的前端部分可复用，但后端需要做存储和运行模型重构。
