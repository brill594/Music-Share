# Backend 路线图

## 模块目标

后端只承担临时文件存储、鉴权、元数据查询和受控文件分发，不承担长期存储和转码。

## 核心职责

- 接收 Android 端上传的已转码音频和元数据
- 生成公开分享链接
- 提供轨道信息查询和受控音频下载
- 管理分享过期和主动终止
- 提供统一密码认证并签发短期凭证

## 1. 基础模型

推荐职责模型：

1. App 上传音频文件和元数据。
2. 后端保存文件与记录。
3. 后端生成 16 位 `share_code`。
4. 后端签发分享页 URL。
5. 前端通过 `share_code` 查询元数据并整文件下载音频。
6. 到期后自动清理。

内部与外部分离：

- `uuid`：内部存储主键
- `share_code`：对外公开路径

## 2. 认证模型

认证目标：

- 只需要密码，不需要用户名
- 同一认证入口自动识别普通权限和管理员权限
- 认证成功后发放短期 `cookie` 或 `api key`

实现建议：

1. 后端启动时随机生成高熵密码。
2. 至少区分：
   - 普通权限密码
   - 管理员密码
3. 提供统一接口：
   - `POST /auth/login`
4. 认证成功后返回：
   - `role`
   - `auth_type`
   - `session_key`
   - `expires_at`
5. 默认有效期约 1 天。

安全要求：

- 密码不出现在公开 URL 中
- 短期凭证必须可过期
- 明文密码不应出现在每个业务请求里

## 3. 核心接口

### `POST /auth/login`

请求体：

- `password`

返回：

- `role`
- `auth_type`
- `session_key`
- `expires_at`

### `POST /upload`

请求内容：

- `file`
- `cover`
- `title`
- `artist`
- `album`
- `duration_ms`
- `audio_mime`
- `client_created_at`
- `expire_after_seconds` 或 `expire_at`

请求头：

- `X-Client-Install-Id`
- 短期 `cookie` 或 `api key`

返回：

- `uuid`
- `share_code`
- `share_url`
- `track_url`
- `stream_url`
- `cover_url`
- `expires_at`
- `status`

### `GET /track/{share_code}`

返回页面展示需要的轨道元数据。

### `GET /stream/{share_code}`

职责：

- 校验分享状态
- 通过 `X-Accel-Redirect` 将整文件下载交给 Nginx

### `GET /cover/{share_code}`

职责：

- 返回封面图
- 无封面则返回 404

### `GET /client/shares`

职责：

- 根据 `client_install_id` 返回客户端自己创建的分享

### `GET /client/shares/{share_code}`

职责：

- 返回该分享的状态、过期时间、剩余时间

### `POST /client/shares/{share_code}/terminate`

职责：

- 提前结束客户端自己创建的分享

### `GET /admin/tracks`

职责：

- 仅管理员可访问
- 返回现有分享音乐列表

### `POST /admin/tracks/{share_code}/terminate`

职责：

- 仅管理员可访问
- 提前结束任意分享

## 4. 存储设计

推荐落盘结构：

- `storage/<uuid>/audio.ogg`
- `storage/<uuid>/cover.jpg`
- `storage/<uuid>/meta.json`

推荐元数据字段：

- `uuid`
- `share_code`
- `client_install_id`
- `title`
- `artist`
- `album`
- `duration_ms`
- `audio_mime`
- `audio_path`
- `cover_path`
- `created_at`
- `expires_at`
- `terminated_at`
- `status`

## 5. 过期与销毁

实现路线：

1. 所有记录写入 SQLite。
2. 记录 `created_at`、`expires_at`。
3. 主动结束分享时立即标记 `terminated`。
4. 定时任务扫描过期和已终止记录。
5. 删除：
   - 音频文件
   - 封面图
   - UUID 目录
   - 数据库记录

## 6. 文件分发

后端文件分发采用：

- FastAPI 做鉴权和状态判断
- Nginx 通过 `X-Accel-Redirect` 发送文件

`GET /stream/{share_code}` 要求：

- 默认用于前端整文件下载
- 应用层不直接长时间传大文件
- Nginx 从 `internal` 目录读取真实文件

## 7. 部署架构

推荐组件：

- API 服务
- Nginx
- SQLite
- 本地文件存储目录

推荐部署边界：

- 单机即可运行
- Nginx 与应用共享受保护音频目录
- 通过 FRP 暴露公网访问

## 8. 安全要求

- 限制上传大小
- 限制允许的音频和图片 MIME
- 限制元数据字段长度
- 限制访问频率
- 不暴露内部文件路径
- `client_install_id` 只通过请求头传输
- 仅管理员凭证可访问管理接口

## 9. 依赖前端与移动端约定

后端需要对接：

- Android 端的认证、上传、分享管理
- Web 前端的 `track` 查询与音频整文件下载

## 10. 验收标准

- 能接收上传并生成 16 位分享路径
- 能统一密码认证并签发约 1 天有效的短期凭证
- 能区分普通权限和管理员权限
- 能通过 `/track/{share_code}` 返回页面数据
- 能通过 `/stream/{share_code}` 配合 Nginx 返回整文件下载
- 能按客户端查询分享状态
- 能让管理员查看并结束任意分享
- 能自动清理过期资源
