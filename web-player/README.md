# Music Share Web Player

`web-player` 是 Music Share 的公开试听前端。这个分支只保留 Cloudflare Pages + Worker API 的部署方式，不再兼容旧的同源静态托管路径。

核心行为保持不变：

- 通过 `GET /track/{share_code}` 拉取元数据
- 下载完整音频文件
- 转为浏览器本地 `Blob URL`
- 使用前端播放器播放

## 技术栈

- `Vue 3`
- `Vite`
- `Vue Router`
- `Pinia`
- `TypeScript`

## 本地开发

环境要求：

- `Node.js 20+`
- `npm 10+`

安装依赖：

```bash
cd web-player
npm install
```

复制环境变量模板：

```bash
cp .env.example .env.local
```

本地联调示例：

```env
VITE_API_BASE_URL=http://localhost:2087/
```

启动开发环境：

```bash
npm run dev
```

说明：

- `VITE_API_BASE_URL` 是必填项
- 它必须指向可直接访问的公开 Worker API 绝对 URL
- 如果未配置或格式错误，页面会直接报配置错误，而不是回退到旧同源路径

## 前后端契约

前端依赖公开接口：

- `GET /track/{share_code}`

该接口至少需要返回以下字段：

- `share_code`
- `title`
- `artist`
- `album`
- `duration_ms`
- `audio_mime`
- `stream_url`
- `cover_url`
- `expires_at`
- `status`

状态约定：

- `404` 表示分享不存在
- `410` 表示分享已过期或已终止

接口要求：

- `stream_url` 应该可以被浏览器直接访问
- `cover_url` 应该是可直接访问的公开地址或 `null`
- `stream_url` 和 `cover_url` 应返回绝对 URL

## Cloudflare Pages 部署

推荐架构：

- `music.example.com` 或 `share.example.com` 部署 `web-player`
- `api.example.com` 部署 Cloudflare Worker API

Pages 配置：

- Build command: `npm run build`
- Build output directory: `dist`
- Environment variable: `VITE_API_BASE_URL=https://api.example.com/`

GitHub Actions 自动部署已提供：

- [.github/workflows/deploy-web-player.yml](/Users/brilliant/repo/Music%20Share_Worker/.github/workflows/deploy-web-player.yml)

启用前至少配置：

- Repository Secrets:
  - `CLOUDFLARE_API_TOKEN`
  - `CLOUDFLARE_ACCOUNT_ID`
- Repository Variables:
  - `CLOUDFLARE_PAGES_PROJECT_NAME`
  - `VITE_API_BASE_URL`

这个 workflow 会：

- 在 `main` 和 `cloud` 分支 push 时自动构建并部署
- 在面向 `main/cloud` 的 pull request 上创建 Pages preview 部署

这个项目已经内置了 Pages 所需的 SPA fallback 文件：

- [public/_redirects](/Users/brilliant/repo/Music%20Share_Worker/web-player/public/_redirects)

其作用是把非静态资源路径回退到 `index.html`，保证以下场景可用：

- 直接访问 `/{shareCode}`
- 刷新 `/{shareCode}`
- 从聊天、浏览器历史或外部应用再次打开分享链接

## 前端行为

当前实现有这些固定约束：

- 请求公开元数据接口时不再依赖 cookie
- 下载音频文件时不再携带 `credentials`
- 元数据接口的 `404/410` 仍然驱动 Not Found / Expired 页面
- 音频对象缺失、Worker 错误页、CORS 失败会保留在当前页面并展示错误提示，不会误跳成分享 404

## 构建与预览

生产构建：

```bash
npm run build
```

构建产物位于：

- `web-player/dist`

本地预览：

```bash
npm run preview
```

如果部署到子路径，可以显式指定 base：

```bash
npm run build -- --base=/music/
```

同时要确保后端生成的分享地址与该子路径一致。

## 验收清单

上线前至少验证：

- Pages Preview 中直接访问 `/{shareCode}` 能正常进入播放页
- 刷新 `/{shareCode}` 不会返回静态 404
- `GET /track/{share_code}` 返回 `200` 时，页面能完成元数据加载和整文件下载
- `GET /track/{share_code}` 返回 `404` 时，进入前端 Not Found 页面
- `GET /track/{share_code}` 返回 `410` 时，进入前端 Expired 页面
- `stream_url` 指向的 Worker/R2 资源异常时，页面显示错误提示而不是误判成分享不存在

## 后续可选项

当前迁移只覆盖“可在 Pages 独立部署并接入 Worker API”的基础能力。后续如果要继续优化，可以再单独处理：

- `public/_headers` 的安全头或缓存策略
- 对 API 域名增加 `preconnect`
- 将整文件下载升级为支持 Range 的边下边播
