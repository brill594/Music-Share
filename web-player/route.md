# Web Player 路线图

## 模块目标

Web Player 负责展示歌曲信息并提供轻量试听体验，不承担下载站或长期内容管理角色。

## 1. 技术栈

前端统一采用：

- `Vue 3`
- `Vite`
- `Vue Router`
- `Pinia`
- `TypeScript`

推荐目录结构：

- `src/app`
- `src/pages`
- `src/components`
- `src/stores`
- `src/services`
- `src/types`
- `src/utils`
- `src/assets`

## 2. 页面结构

推荐首版页面：

- `TrackPage.vue`
- `ExpiredPage.vue`
- `NotFoundPage.vue`

推荐组件：

- `TrackHero.vue`
- `AudioPlayer.vue`
- `MetadataCopyPanel.vue`
- `StatusBanner.vue`

## 3. 路由与数据获取

路由：

- `/:shareCode`
- `/expired`
- `/:pathMatch(.*)*`

实现流程：

1. 页面解析 `shareCode`
2. 请求 `GET /track/{share_code}`
3. 成功后请求 `stream_url`
4. 完整下载音频文件
5. 将响应转为本地 `Blob URL`
6. 使用隐藏 `audio` 元素播放

页面状态：

- `loading`
- `ready`
- `expired`
- `error`

## 4. 播放器方案

核心要求：

1. 不使用原生可见 `<audio controls>`。
2. 采用页面内嵌小型播放器。
3. 音频不是流式边播，而是页面加载时整文件下载。
4. 下载完成后使用本地 `Blob URL` 播放。

播放器交互：

- 播放 / 暂停
- 拖拽进度条
- 当前时间 / 总时长
- 播放模式切换

播放模式仅两种：

- 播放完成停止
- 单曲循环

因为音频已在前端本地：

- 拖拽进度条不需要再次请求后端
- 暂停和恢复不需要再次请求后端

## 5. UI 与体验

视觉方向：

1. 背景使用封面模糊图层。
2. 中央主体用单卡片布局。
3. 重点突出歌曲名、艺术家、专辑。
4. 页面提示链接会过期。

文本复制：

- 支持勾选：
  - 歌曲名
  - 艺术家
  - 专辑
- 默认至少勾选歌曲名和艺术家

## 6. 交互细节

必须处理：

1. 加载态：
   - 元数据加载中
   - 音频整文件下载中
2. 失效态：
   - 明确提示链接已过期
3. 异常态：
   - 网络错误可重试
4. 无封面态：
   - 使用默认占位图
5. 播放模式切换：
   - 停止
   - 单曲循环

## 7. 前后端联调约定

Web 端依赖后端：

- `GET /track/{share_code}`
- `GET /stream/{share_code}`
- `GET /cover/{share_code}`

联调要求：

- `track` 返回 `200 / 404 / 410`
- `stream_url` 支持一次性完整下载音频文件
- `expires_at` 返回标准时间格式
- `cover_url` 失效时前端可降级

## 8. 体验边界

这个模块只做试听展示，不做：

- 长期内容管理
- 文件下载中心
- 历史库
- 批量列表

## 9. 验收标准

- 能基于 `shareCode` 正确请求并渲染歌曲数据
- 能完整下载音频并生成本地播放源
- 自定义播放器支持暂停、拖拽进度条、停止、单曲循环
- 页面具备加载态、失效态、异常态
- 手机端和桌面端都可用
