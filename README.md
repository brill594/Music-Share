# Music Share

Music Share 是一套临时音频分享工具，包含：

- `android-app/` Android 客户端
- `backend/` Cloudflare Worker 后端
- `web-player/` 公开试听前端

当前后端架构已收敛为：

- `Cloudflare Workers`
- `D1`
- `R2`
- `Cron Triggers`

不再保留旧的单机 `FastAPI + SQLite + 本地文件系统 + Nginx` 部署链路。

## 功能概览

- 统一密码登录，区分普通用户与管理员
- 上传音频、封面和歌曲元数据
- 生成公开分享码
- 对外提供：
  - `GET /track/{share_code}`
  - `GET /stream/{share_code}`
  - `GET /cover/{share_code}`
- 对客户端提供：
  - `GET /client/shares`
  - `GET /client/shares/{share_code}`
  - `POST /client/shares/{share_code}/terminate`
- 对管理员提供：
  - `GET /admin/tracks`
  - `POST /admin/tracks/{share_code}/terminate`

## 开发

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
npm run dev
```

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

## 部署概览

后端通过 Wrangler 部署到 Cloudflare：

```bash
cd backend
npm run deploy
```

部署前需要完成：

- D1 数据库创建与迁移
- R2 bucket 创建与绑定
- Worker 环境变量配置

项目级使用与部署总览见 [DEPLOYMENT.md](/Users/brilliant/repo/Music%20Share_Worker/DEPLOYMENT.md)。

更具体的模块说明见：

- [backend/README.md](/Users/brilliant/repo/Music%20Share_Worker/backend/README.md)
- [web-player/README.md](/Users/brilliant/repo/Music%20Share_Worker/web-player/README.md)
