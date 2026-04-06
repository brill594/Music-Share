# Music Share

Music Share 是一套面向临时分享场景的音频分享系统，包含 Android 上传端、Cloudflare Worker 后端和公开试听页面。

## 项目组成

- `android-app/` Android 客户端，负责登录、发起分享和管理分享状态
- `backend/` Cloudflare Workers API，负责鉴权、上传、分享访问控制和定时清理
- `web-player/` Web Player，负责公开展示歌曲信息并提供试听体验

## 核心能力

- 统一密码登录，区分普通用户与管理员
- 上传音频、封面和基础元数据
- 生成公开分享链接
- 支持分享过期和提前终止
- 提供公开试听页和客户端管理接口

## 运行架构

- `Cloudflare Workers`
- `D1`
- `R2`
- `Cloudflare Pages`

## 本地启动

后端：

```bash
cd backend
npm install
npm run dev
```

Web Player：

```bash
cd web-player
npm install
cp .env.example .env.local
npm run dev
```

Android：

```bash
cd android-app
./gradlew :app:assembleDebug
```

## 部署

后端使用 Wrangler 部署到 Cloudflare Workers，Web Player 部署到 Cloudflare Pages。部署前需要准备 D1、R2、Pages 项目以及对应的环境变量和密钥配置。

完整部署说明见 [DEPLOYMENT.md](/Users/brilliant/repo/Music%20Share_Worker/DEPLOYMENT.md)。

模块说明见 [backend/README.md](/Users/brilliant/repo/Music%20Share_Worker/backend/README.md) 和 [web-player/README.md](/Users/brilliant/repo/Music%20Share_Worker/web-player/README.md)。
