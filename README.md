# Music Share

Music Share 是一套临时音频分享工具，包含：

- Android 客户端：读取当前播放曲目、上传音频/封面/元数据、管理自己的分享
- 后端服务：鉴权、会话管理、分享查询、音频分发、过期清理
- Web Player：公开试听页，按分享码展示歌曲信息并播放音频

当前部署路径已经统一为裸金属服务器部署：`FastAPI + SQLite + 本地文件系统 + Nginx` 提供 API 和受控媒体分发，`web-player/dist` 由同一个 Nginx 站点提供静态页面。

## 仓库结构

- `android-app/` Android 客户端
- `backend/` FastAPI 后端、SQLite/本地文件存储、Nginx/Certbot 配置脚本
- `web-player/` Vue 3 + Vite 公开试听前端
- `install.sh` Linux 服务器一键安装脚本，会安装后端并构建前端
- `start.sh` 已部署 `systemd` 服务的启动/停止/查看日志脚本

## 核心能力

- 统一密码登录，自动区分普通用户与管理员
- 上传音频、封面和歌曲元数据
- 生成公开分享码和公开试听页
- 公开接口：`/track/{share_code}`、`/stream/{share_code}`、`/cover/{share_code}`、`/background`
- 客户端管理接口：查看自己的分享、查看单条分享状态、提前终止分享
- 管理员接口：查看全部分享、终止任意分享、配置全局背景图、查看/更新用量阈值
- 自动清理过期分享、终止分享和过期 session

## 本地开发

后端：

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.asgi:app --host 127.0.0.1 --port 2087
```

Web Player：

```bash
cd web-player
npm install
npm run dev
```

如果没有配置 `VITE_API_BASE_URL`，Web Player 会使用当前页面同源地址作为 API 根地址；这也是裸金属部署的默认方式。

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

## 服务器部署

根目录提供了面向 Linux 服务器的部署脚本：

```bash
sudo bash ./install.sh
```

安装脚本会自动完成：

- 检查并安装 Python、Node.js、Nginx、Certbot 等基础依赖
- 创建后端虚拟环境并安装 Python 依赖
- 安装前端依赖并构建 `web-player/dist`
- 创建 `music-share` 系统用户
- 准备运行数据目录，默认是 `/var/lib/music-share`
- 调用 `backend/setup.sh` 配置 Nginx、证书和公开地址
- 安装 `music-share-backend.service`
- 安装并启用 Certbot 自动续期 timer

部署完成后通过根目录脚本管理服务：

```bash
sudo bash ./start.sh
sudo bash ./start.sh status
sudo bash ./start.sh logs
sudo bash ./start.sh stop
```

完整部署说明见 `DEPLOYMENT.md`。后端细节见 `backend/README.md`，Web Player 细节见 `web-player/README.md`。
