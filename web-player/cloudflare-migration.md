# Web Player 迁移路线规划（Cloudflare Pages）

## 目标

将 `web-player` 从“依赖同源 Nginx 反代的静态前端”迁移为“部署在 Cloudflare Pages 的独立 SPA”，并与新的 Cloudflare Worker API 对接，同时尽量保持现有试听体验不变。

目标平台：

- 静态站点：Cloudflare Pages
- API 来源：Cloudflare Workers
- 媒体存储：R2
- 元数据来源：D1

## 当前实现与迁移影响点

当前前端的 Cloudflare 迁移关键约束集中在这几个文件：

- `src/services/api.ts`
  - 默认按 `window.location.origin` 访问 API
  - 公共请求也带了 `credentials: "include"`
- `src/stores/track.ts`
  - 先请求 `GET /track/{share_code}`
  - 再完整下载 `stream_url`
  - 下载完成后转为本地 `Blob URL`
- `src/app/router.ts`
  - 使用 history 路由，要求静态托管支持 SPA fallback

这意味着前端迁移不是“只换部署平台”：

1. 必须先解除对同源反代的隐式依赖。
2. 必须明确 `Pages` 与 `Worker API` 的域名关系。
3. 必须确认现有“整文件下载后播放”的模型继续成立。

## 推荐目标架构

推荐优先采用分域部署，而不是首阶段强行同域：

- `share.example.com` 或 `music.example.com` -> Cloudflare Pages
- `api.example.com` -> Cloudflare Worker

推荐理由：

- 与当前仓库结构一致，前后端可独立迁移、独立回滚。
- `web-player` 只访问公开接口，不必继续绑定 cookie 同源能力。
- 便于先完成 Pages 上线，再逐步替换后端为 Worker。

如果后续必须保持同域：

- 可以在 Pages 中使用 Functions Advanced Mode 的 `_worker.js` 接管 `/api/*`
- 但这会把静态资源与 API 路由耦合到一个 Pages 项目里
- 不适合作为首阶段迁移方案

## 分阶段路线

### 阶段 0：冻结前后端契约

先把前端真正依赖的后端契约固定下来，避免迁移过程中接口语义反复变化。

必须冻结的字段和行为：

- `GET /track/{share_code}` 继续返回：
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
- 失效语义保持不变：
  - `404` 表示不存在
  - `410` 表示已过期或已终止
- `stream_url` 首阶段仍允许“整文件下载”

阶段产物：

- 一份与 Worker 后端共同遵守的公开接口说明

### 阶段 1：解除前端对同源与 cookie 的依赖

这是前端迁移的第一优先级，应该先于 Pages 上线。

建议修改点：

- `src/services/api.ts`
  - 明确区分 `VITE_API_BASE_URL` 和当前页面地址
  - 对公开接口默认改为不依赖 `credentials: "include"`
  - 保证 `stream_url`、`cover_url` 为绝对 URL 时可以直接访问
- `.env.example`
  - 补充 Cloudflare 场景示例，例如：
    - `VITE_API_BASE_URL=https://api.example.com/`
- 错误处理
  - 将 CORS / Worker 错误页 / R2 对象不可用的提示语与当前 Nginx 文案解耦

阶段目标：

- 即使前端部署在 Pages、后端还在旧服务上，也能通过显式 `VITE_API_BASE_URL` 联调

### 阶段 2：让 Pages 承载纯静态 SPA

这一阶段不改播放器逻辑，只解决部署面问题。

建议落地方式：

- 构建命令：`npm run build`
- 输出目录：`dist`
- Pages 项目直接托管 `dist`

需要补齐的 Pages 适配项：

- SPA fallback
  - Pages 默认对没有顶层 `404.html` 的项目按 SPA 方式处理
  - 但仍建议显式增加 `_redirects`，例如 `/* /index.html 200`
- 自定义响应头
  - 如需额外安全头或缓存策略，使用 `_headers`
- 预览环境
  - 每个分支使用 Pages Preview 验证真实分享链接访问和刷新行为

阶段验收：

- 直接访问 `/{shareCode}` 正常进入页面
- 刷新 `/{shareCode}` 不返回 404
- `ExpiredPage` 与 `NotFoundPage` 路由仍按前端逻辑生效

### 阶段 3：接入 Worker API

Pages 可用后，再把 API 终点从旧 FastAPI 切到 Worker。

前端需要确认的兼容点：

- `GET /track` 的返回结构不变
- `stream_url` 与 `cover_url` 指向新的 Worker 域名
- Worker 对 `Content-Type`、`Content-Length`、缓存头的返回足够稳定

建议前端在这一阶段保持现有播放器模型不变：

1. 请求元数据
2. 下载完整音频
3. 转本地 `Blob URL`
4. 再显示播放器

这样可以把风险集中在基础设施迁移，而不是同时重写播放策略。

### 阶段 4：体验与性能优化

当 Pages + Worker 跑稳定后，再考虑优化。

可选优化项：

- 将 `downloadAudio()` 的进度展示与 Worker/R2 的 `Content-Length` 对齐
- 为 `cover_url` 增加更明确的失败回退策略
- 给 API 域名增加 `preconnect`
- 评估是否从“整文件下载”升级为“支持 Range 的边下边播”

这一阶段不建议和基础迁移一起做。当前播放器与状态管理已经围绕“完整下载后播放”建立，先保兼容，再谈重构。

### 阶段 5：切流与回滚

建议切流顺序：

1. Pages Preview 连旧后端验证
2. Pages Production 连新 Worker 预发布域名验证
3. 小流量或内部域名验证真实分享链接
4. 正式切换公开分享域名
5. 保留旧静态部署作为短期回滚路径

回滚原则：

- 前端回滚应只回滚 Pages 部署版本
- 不和后端数据迁移绑死在同一时刻

## 目录内建议改动顺序

建议按下面顺序改动 `web-player`：

1. `src/services/api.ts`
2. `.env.example`
3. 新增 `public/_redirects`
4. 如有需要新增 `public/_headers`
5. `README.md` 补充 Pages 部署方式

这样能先完成“可部署到 Pages”，再做体验优化。

## 风险清单

- 当前公共接口请求带 `credentials`，若迁到独立 API 域名会引入不必要的 CORS 复杂度。
- 前端仍是“整文件下载后播放”，首包时间仍取决于音频体积。
- 如果后端后续把 `stream_url` 变为短期签名链接，前端需要确认重试策略与过期处理。
- 如果 Pages 项目后续引入顶层 `404.html`，会改变默认 SPA fallback 行为，需要重新验证路由。

## 验收标准

- `web-player` 能在 Cloudflare Pages 独立部署。
- 分享页路径可直接访问、刷新、复制打开。
- 在分域 API 模式下，页面仍能加载轨道信息、封面和音频。
- `404` 与 `410` 行为与当前页面跳转逻辑一致。
- 不依赖 Nginx 同源反代也能完成播放。

## 决策建议

前端迁移的核心不是 Pages，而是“先去掉同源假设”。只要这一步做好，Pages 上线本身很轻；如果这一步不做，后面会被 CORS、cookie 和路由问题反复拖住。

## 参考资料

- Cloudflare Pages Redirects:
  - https://developers.cloudflare.com/pages/configuration/redirects/
- Cloudflare Pages Headers:
  - https://developers.cloudflare.com/pages/configuration/headers/
- Cloudflare Pages Configuration:
  - https://developers.cloudflare.com/pages/configuration/
- Pages Functions / Advanced Mode:
  - https://developers.cloudflare.com/pages/functions/get-started/
  - https://developers.cloudflare.com/pages/how-to/refactor-a-worker-to-pages-functions/
