# Music Share Web Player

`web-player` 是 Music Share 的公开试听前端，负责：

- 根据分享码拉取歌曲元数据
- 完整下载音频文件并转成浏览器本地 `Blob URL`
- 提供自定义播放器和歌曲信息展示
- 根据设备形态在移动端 / PC 端布局之间自动切换

当前实现基于：

- `Vue 3`
- `Vite`
- `Vue Router`
- `Pinia`
- `TypeScript`

## 目录说明

- `src/app` 应用入口和路由
- `src/pages` 页面组件
- `src/components` 业务组件
- `src/stores` 状态管理
- `src/services` API 请求和音频下载逻辑
- `src/utils` 工具函数

## 本地开发

环境要求：

- `Node.js 20+`
- `npm 10+`

安装依赖：

```bash
cd web-player
npm install
```

启动开发环境：

```bash
npm run dev
```

默认情况下前端会把 API 基址视为当前页面同源地址，即：

- 页面在 `https://share.example.com` 时，会请求 `https://share.example.com/track/{share_code}`
- 不额外配置 `VITE_API_BASE_URL` 也可以工作

如果你在本地把前端和后端分开跑，可以创建 `.env.local`：

```bash
cp .env.example .env.local
```

示例：

```env
VITE_API_BASE_URL=http://localhost:2087/
```

## 构建

生产构建：

```bash
npm run build
```

构建产物位于：

- `web-player/dist`

本地预览构建结果：

```bash
npm run preview
```

如果需要部署到子路径，例如 `/music/`，可以显式指定 Vite base：

```bash
npm run build -- --base=/music/
```

同时要确保后端生成的分享地址也指向这个子路径，见下面的“后端联动配置”。

## 路由与部署要求

Web Player 使用 `history` 路由，主要路径有：

- `/:shareCode`
- `/expired`
- 其他路径回落到前端 404 页面

因此静态托管必须支持 SPA fallback：

- 命中真实静态资源时直接返回文件
- 其他非 API 路径统一回退到 `index.html`

如果未配置 fallback，直接访问分享链接时会得到服务器 404，而不是前端页面。

## 推荐部署方式

当前后端没有启用 CORS，中短期内推荐使用“前端静态文件 + 后端 API 同源反代”方案。

推荐原因：

- 前端默认就是按同源请求 API
- 不需要额外处理跨域
- `GET /track/{share_code}` 返回的 `stream_url`、`cover_url` 能直接在同域下工作
- 更适合浏览器完整下载音频文件的场景

## 与后端联动配置

部署前建议确认后端至少配置以下环境变量：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `MUSIC_SHARE_USE_X_ACCEL_REDIRECT`

常见配置方式：

1. 前后端同域部署在 `https://share.example.com`

```env
MUSIC_SHARE_PUBLIC_API_BASE_URL=https://share.example.com
MUSIC_SHARE_PUBLIC_SHARE_BASE_URL=https://share.example.com
MUSIC_SHARE_USE_X_ACCEL_REDIRECT=true
```

2. 前端部署在子路径 `https://share.example.com/music/`

```env
MUSIC_SHARE_PUBLIC_API_BASE_URL=https://share.example.com
MUSIC_SHARE_PUBLIC_SHARE_BASE_URL=https://share.example.com/music
MUSIC_SHARE_USE_X_ACCEL_REDIRECT=true
```

第二种场景下，前端构建也要使用对应 base：

```bash
npm run build -- --base=/music/
```

## Nginx 同源部署示例

下面的示例适合把 Web Player 构建产物和后端统一挂在同一个域名下：

```nginx
server {
    listen 80;
    server_name share.example.com;

    root /srv/music-share/web-player/dist;
    index index.html;

    location /assets/ {
        try_files $uri =404;
        access_log off;
        expires 7d;
        add_header Cache-Control "public, max-age=604800, immutable";
    }

    location /internal-media/ {
        internal;
        alias /srv/music-share/backend/data/storage/;
    }

    location ~ ^/(track|stream|cover|auth|upload|client|admin)(/|$) {
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_pass http://127.0.0.1:2087;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

说明：

- `/assets/` 用于返回 Vite 构建出的静态资源
- `/internal-media/` 是后端 `X-Accel-Redirect` 方案必须的内部映射
- `/track`、`/stream`、`/cover` 等 API 请求会被转发到后端
- 其余路径如 `/abcd1234`、`/expired` 会回退到前端 `index.html`

如果你已经在 API 侧有单独的 Nginx，可以把上面的静态托管逻辑合并进去；`/internal-media/` 的配置思路与 [backend/nginx.example.conf](/Users/brilliant/repo/Music%20Share/backend/nginx.example.conf) 一致。

## 分离域名部署说明

理论上前端也可以独立部署到 CDN 或另一个域名，并通过 `VITE_API_BASE_URL` 指向后端 API。

但以当前仓库代码为准，这不是默认推荐方案，因为：

- 后端当前没有配置 CORS
- 前端请求会携带 `credentials: "include"`
- `stream_url` 和 `cover_url` 也会走后端公开地址

如果确实要分离域名，至少需要你在后端补齐：

- 合法的 CORS 响应头
- 对应域名的 `Access-Control-Allow-Origin`
- `Access-Control-Allow-Credentials`

在没有这些改造前，建议坚持同源部署。

## 发布步骤建议

1. 在服务器上部署并验证后端可用。
2. 设置后端公开地址：
   `MUSIC_SHARE_PUBLIC_API_BASE_URL` 和 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`。
3. 在 `web-player` 执行 `npm install && npm run build`。
4. 将 `dist/` 上传到静态目录，例如 `/srv/music-share/web-player/dist`。
5. 配置 Nginx：
   需要同时覆盖静态资源、SPA fallback、API 反代和 `/internal-media/`。
6. 访问一个真实分享链接进行验证：
   检查元数据、封面、完整音频下载和播放器是否正常工作。

## 验证清单

建议上线前至少确认：

- 打开 `/{shareCode}` 能正常进入播放页
- 刷新 `/{shareCode}` 不会返回 Nginx/静态服务器 404
- `GET /track/{share_code}` 返回 200 时，页面能进入 ready 状态
- 音频可以完整下载并开始播放
- 链接失效时能跳转到 `/expired`
- 手机、平板、折叠屏和桌面端布局切换符合预期

## 常见问题

### 1. 直接访问分享链接返回 404

通常是因为静态服务器没有配置 SPA fallback。

需要确保：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

### 2. 页面能打开，但请求 `/track/...` 失败

优先检查：

- 反向代理是否把 `/track`、`/stream`、`/cover` 转给了后端
- `MUSIC_SHARE_PUBLIC_API_BASE_URL` 是否配置成了外部可访问地址
- 前端是否错误配置了 `VITE_API_BASE_URL`

### 3. 音频接口返回 200，但浏览器无法播放

优先检查：

- 后端音频 MIME 类型是否正确
- Nginx 是否保留了后端返回头
- `X-Accel-Redirect` 的 `/internal-media/` 映射是否正确

### 4. 前端在子路径下打开后资源 404

通常是构建时没有带上正确的 `base`。例如部署到 `/music/` 时，需要：

```bash
npm run build -- --base=/music/
```

同时还要把后端的 `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL` 配成：

```env
https://share.example.com/music
```
