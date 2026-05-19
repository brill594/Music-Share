# Music Share Web Player

`web-player` 是 Music Share 的公开试听前端，用于按分享码加载歌曲元数据、展示封面/背景并播放音频。

当前默认部署方式是和 FastAPI 后端同源部署在同一个 Nginx 站点下：

- 静态文件来自 `web-player/dist`
- API 路径由 Nginx 代理到 `backend/` 的 FastAPI 服务
- 未设置 `VITE_API_BASE_URL` 时，前端会自动使用当前页面同源地址作为 API 根地址

## 本地开发

安装依赖：

```bash
npm install
```

如果后端运行在本地 `127.0.0.1:2087`，开发环境通常需要显式设置 API 地址：

```env
VITE_API_BASE_URL=http://127.0.0.1:2087/
```

启动开发服务器：

```bash
npm run dev
```

构建生产静态文件：

```bash
npm run build
```

## API 配置

`VITE_API_BASE_URL` 是可选项：

- 已配置时必须是合法的绝对 URL，例如 `https://music.example.com/` 或 `http://127.0.0.1:2087/`
- 未配置时使用 `window.location.origin + "/"`，适合裸金属同源部署

跨域使用 `VITE_API_BASE_URL` 时，后端需要通过 `MUSIC_SHARE_CORS_ALLOWED_ORIGINS` 或 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 允许当前前端 origin。

公开播放页会调用：

- `GET /track/{share_code}` 获取元数据
- `GET /stream/{share_code}` 下载/播放音频
- `GET /cover/{share_code}` 获取封面图
- `GET /background` 获取全局背景图（当后端在 track payload 中返回 `background_url` 时）

## 裸金属生产部署

根目录 `install.sh` 会自动在 `web-player/` 中执行依赖安装和 `npm run build`，然后 `backend/setup.sh` 会把构建产物路径写入 Nginx 配置。

生产 Nginx 站点应满足：

- `/assets/` 等静态资源直接从 `web-player/dist` 读取
- `/` 前端路由回退到 `index.html`
- `/auth`、`/upload`、`/client`、`/admin`、`/track`、`/stream`、`/cover`、`/background` 代理到 FastAPI 后端

## 验收要点

- 直接访问 `/{shareCode}` 能进入播放页
- 刷新 `/{shareCode}` 不返回静态 404
- `GET /track/{share_code}` 返回 `200` 时，页面能完成元数据加载和音频下载
- `GET /track/{share_code}` 返回 `404` 时，进入 Not Found 状态
- `GET /track/{share_code}` 返回 `410` 时，进入 Expired 状态
- `stream_url` 指向的音频资源异常时，页面显示音频错误提示，不误判成分享不存在
