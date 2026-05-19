# Music Share Backend

Music Share 的后端服务，负责临时音频分享的鉴权、元数据查询、受控音频分发和过期清理，不负责长期存储与转码。

当前实现基于：

- `FastAPI`
- `SQLite`
- 本地文件系统存储
- `Nginx + X-Accel-Redirect` 受控分发

## 功能概览

- 统一密码登录，自动识别普通权限和管理员权限
- 返回短期 `session_key`，并同步写入 `HttpOnly Cookie`
- 接收 Android 端上传的音频、封面和元数据
- 生成 16 位公开 `share_code`
- 对外提供：
  - `GET /track/{share_code}`
  - `GET /stream/{share_code}`
  - `GET /cover/{share_code}`
  - `GET /background`
- 对客户端提供：
  - 自己创建的分享列表
  - 单条分享状态查询
  - 提前终止自己的分享
- 对管理员提供：
  - 当前所有分享列表
  - 提前终止任意分享
  - 配置全局背景图
  - 查看/更新用量阈值
- 定时删除：
  - 已过期分享
  - 已终止分享
  - 过期 session

## 目录结构

- `app/` FastAPI 服务代码
- `tests/` 接口测试
- `nginx.example.conf` Nginx 示例配置
- `route.md` 后端路线图
- `data/` 运行时数据目录，默认包含：
  - `music_share.sqlite3`
  - `runtime-secrets.json`
  - `storage/<uuid>/audio.*`
  - `storage/<uuid>/cover.*`
  - `storage/<uuid>/meta.json`

## 快速开始

### 1. 安装依赖

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

### 2. 生产部署

生产环境请从仓库根目录运行统一安装脚本：

```bash
sudo bash ./install.sh
```

根目录安装脚本会构建 `web-player/dist`、创建服务用户、调用 `backend/setup.sh` 写入 Nginx/证书/API 公开地址配置，并安装 `systemd` 服务。

`backend/setup.sh` 是安装脚本使用的底层配置脚本，不作为独立生产入口；直接运行它不会安装根目录 `start.sh` 需要的 `systemd` 服务。

### 3. 本地启动后端服务

本地开发可直接启动 FastAPI，不需要 Nginx/Certbot：

```bash
cd backend
source .venv/bin/activate
uvicorn app.asgi:app --host 127.0.0.1 --port 2087
```

`backend/start.sh` 仅用于手动调试裸后端进程；生产服务管理请使用根目录 `start.sh`。

### 4. 首次启动后的密码

如果没有显式配置：

- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

服务会在首次启动时随机生成缺失的普通密码和管理员密码，并写入：

- `backend/data/runtime-secrets.json`

后续启动会复用该文件中的生成密码。生产环境建议用环境变量固定密码；如果密码来自环境变量，后端不会把这些明文环境变量写回运行时密钥文件。

### 5. 查看接口文档

FastAPI 默认开放：

- `GET /docs`
- `GET /openapi.json`

本地启动后可访问：

- [http://127.0.0.1:2087/docs](http://127.0.0.1:2087/docs)

## 认证模型

### 登录

接口：

- `POST /auth/login`

请求体：

```json
{
  "password": "your-password"
}
```

返回字段：

- `role`
- `auth_type`
- `session_key`
- `expires_at`

当前实现中：

- `role` 为 `user` 或 `admin`
- `auth_type` 固定为 `api_key`
- session 默认有效期为 `86400` 秒，也就是 1 天

### 后续请求如何带 session

后续业务请求支持三种方式：

- 请求头 `X-Session-Key: <session_key>`
- 请求头 `Authorization: Bearer <session_key>`
- 登录接口写入的 cookie

### 客户端归属校验

以下接口要求携带：

- `X-Client-Install-Id`

它用于标识 Android 安装实例，并做“只能管理自己分享”的校验。

需要该请求头的接口：

- `POST /upload`
- `GET /client/shares`
- `GET /client/shares/{share_code}`
- `POST /client/shares/{share_code}/terminate`

格式限制：

- 长度 `8-128`
- 允许字符：`A-Z a-z 0-9 . _ -`

## 典型调用流程

### 1. 登录

```bash
curl -X POST http://127.0.0.1:2087/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"password":"your-user-password"}'
```

示例返回：

```json
{
  "role": "user",
  "auth_type": "api_key",
  "session_key": "....",
  "expires_at": "2026-04-05T13:00:00Z"
}
```

### 2. 上传音频

```bash
curl -X POST http://127.0.0.1:2087/upload \
  -H 'X-Session-Key: <session_key>' \
  -H 'X-Client-Install-Id: android-install-001' \
  -F 'file=@./demo.ogg;type=audio/ogg' \
  -F 'cover=@./cover.jpg;type=image/jpeg' \
  -F 'title=Demo Song' \
  -F 'artist=Demo Artist' \
  -F 'album=Demo Album' \
  -F 'duration_ms=213000' \
  -F 'audio_mime=audio/ogg' \
  -F 'expire_after_seconds=86400'
```

上传返回：

- `uuid`
- `share_code`
- `share_url`
- `track_url`
- `stream_url`
- `cover_url`
- `created_at`
- `expires_at`
- `status`

说明：

- `cover` 可省略
- `expire_after_seconds` 与 `expire_at` 二选一
- `client_created_at` 可选，要求 ISO 8601

### 3. Web 端获取公开信息

```bash
curl http://127.0.0.1:2087/track/<share_code>
```

这个接口适合前端页面加载时获取：

- 歌名
- 艺术家
- 专辑
- 时长
- `stream_url`
- `cover_url`
- `expires_at`

### 4. Web 端下载音频

```bash
curl -I http://127.0.0.1:2087/stream/<share_code>
```

如果启用了 `X-Accel-Redirect`，应用会返回类似头：

```http
X-Accel-Redirect: /internal-media/<uuid>/audio.ogg
Content-Disposition: inline; filename*=UTF-8''Demo_Song.ogg
```

这意味着真正的文件读取交给 Nginx，不由应用进程长时间直出。

### 5. 查询自己的分享

```bash
curl http://127.0.0.1:2087/client/shares \
  -H 'X-Session-Key: <session_key>' \
  -H 'X-Client-Install-Id: android-install-001'
```

### 6. 提前终止自己的分享

```bash
curl -X POST http://127.0.0.1:2087/client/shares/<share_code>/terminate \
  -H 'X-Session-Key: <session_key>' \
  -H 'X-Client-Install-Id: android-install-001'
```

### 7. 管理员列出全部分享

```bash
curl http://127.0.0.1:2087/admin/tracks \
  -H 'X-Session-Key: <admin_session_key>'
```

### 8. 管理员终止任意分享

```bash
curl -X POST http://127.0.0.1:2087/admin/tracks/<share_code>/terminate \
  -H 'X-Session-Key: <admin_session_key>'
```

## 接口说明

### `POST /auth/login`

用途：

- 输入一段密码
- 后端自动识别这是普通用户密码还是管理员密码
- 返回短期 session

常见状态码：

- `200` 登录成功
- `401` 密码错误
- `429` 登录频率过高

### `POST /upload`

请求类型：

- `multipart/form-data`

表单字段：

- `file` 必填，音频文件
- `cover` 可选，封面文件
- `title` 必填
- `artist` 可选
- `album` 可选
- `duration_ms` 必填
- `audio_mime` 必填
- `client_created_at` 可选
- `expire_after_seconds` 可选
- `expire_at` 可选

常见状态码：

- `200` 上传成功
- `401` session 缺失或无效
- `413` 文件超出大小限制
- `422` MIME、时长、表单字段或 `X-Client-Install-Id` 不合法
- `429` 上传频率过高

### `GET /track/{share_code}`

用途：

- 给 Web 播放页加载歌曲元数据

常见状态码：

- `200` 可用
- `404` `share_code` 不存在
- `410` 分享已过期或已终止
- `429` 访问频率过高

### `GET /stream/{share_code}`

用途：

- 返回音频内容
- 默认通过 Nginx 内部重定向进行真实文件分发

常见状态码：

- `200` 可用
- `404` `share_code` 不存在
- `410` 分享已过期或已终止
- `429` 访问频率过高

### `GET /cover/{share_code}`

用途：

- 返回封面图

常见状态码：

- `200` 有封面
- `404` 不存在或该分享没有封面
- `410` 分享已过期或已终止


### `GET /background`

用途：

- 返回管理员配置的全局播放页背景图

常见状态码：

- `200` 已配置背景图
- `404` 尚未配置背景图

### `GET /client/shares`

用途：

- 查询某个客户端自己上传的分享列表

要求：

- 有效 session
- `X-Client-Install-Id`

### `GET /client/shares/{share_code}`

用途：

- 查询自己某条分享的状态、剩余时间、终止状态

要求：

- 有效 session
- `X-Client-Install-Id`

### `POST /client/shares/{share_code}/terminate`

用途：

- 提前终止当前客户端自己创建的分享

要求：

- 有效 session
- `X-Client-Install-Id`

### `GET /admin/tracks`

用途：

- 列出后端当前全部分享

要求：

- 管理员 session

### `POST /admin/tracks/{share_code}/terminate`

用途：

- 提前终止任意分享

要求：

- 管理员 session

### `GET /admin/background`

用途：

- 查看全局背景图是否已配置，以及公开访问 URL

要求：

- 管理员 session

### `POST /admin/background`

用途：

- 上传新的全局背景图，表单字段名为 `background`

要求：

- 管理员 session

### `GET /admin/usage`

用途：

- 返回 Android 管理页使用的用量阈值和本机存储占用摘要

要求：

- 管理员 session

### `POST /admin/usage`

用途：

- 更新 Android 管理页使用的用量阈值配置

要求：

- 管理员 session

## 环境变量

常用环境变量如下。

### 路径与基础配置

- `MUSIC_SHARE_DATA_ROOT`
  - 默认：`backend/data`
  - 作用：运行时总目录
- `MUSIC_SHARE_DATABASE_PATH`
  - 默认：`$MUSIC_SHARE_DATA_ROOT/music_share.sqlite3`
- `MUSIC_SHARE_STORAGE_ROOT`
  - 默认：`$MUSIC_SHARE_DATA_ROOT/storage`
- `MUSIC_SHARE_RUNTIME_SECRET_PATH`
  - 默认：`$MUSIC_SHARE_DATA_ROOT/runtime-secrets.json`

### 鉴权与 session

- `MUSIC_SHARE_USER_PASSWORD`
  - 默认：启动时随机生成
- `MUSIC_SHARE_ADMIN_PASSWORD`
  - 默认：启动时随机生成
- `MUSIC_SHARE_SESSION_TTL_SECONDS`
  - 默认：`86400`
- `MUSIC_SHARE_SESSION_COOKIE_NAME`
  - 默认：`music_share_session`

### 分享有效期与清理

- `MUSIC_SHARE_SHARE_DEFAULT_TTL_SECONDS`
  - 默认：`86400`
- `MUSIC_SHARE_SHARE_MAX_TTL_SECONDS`
  - 默认：`2592000`
- `MUSIC_SHARE_CLEANUP_INTERVAL_SECONDS`
  - 默认：`60`

### 上传限制

- `MUSIC_SHARE_MAX_AUDIO_UPLOAD_BYTES`
  - 默认：`67108864`，也就是 `64 MiB`
- `MUSIC_SHARE_MAX_COVER_UPLOAD_BYTES`
  - 默认：`8388608`，也就是 `8 MiB`
- `MUSIC_SHARE_MAX_DURATION_MS`
  - 默认：`43200000`，也就是 `12 小时`

### 公开 URL 生成

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
  - 作用：生成返回给客户端的 `track_url`、`stream_url`、`cover_url`、`background_url`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
  - 作用：生成返回给客户端的 `share_url`，并默认加入 CORS 允许来源
- `MUSIC_SHARE_CORS_ALLOWED_ORIGINS`
  - 作用：额外允许跨域访问 API 的前端来源，多个来源用英文逗号分隔

如果公开 URL 不设置，后端会根据当前请求的 `Host` 推导返回 URL。开发环境可行，公网部署通常应显式配置。裸金属同源部署不依赖 CORS；本地 Vite 开发默认允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`。

### 文件分发

- `MUSIC_SHARE_USE_X_ACCEL_REDIRECT`
  - 默认：`true`
  - 为 `false` 时，应用直接输出音频文件
- `MUSIC_SHARE_INTERNAL_MEDIA_PREFIX`
  - 默认：`/internal-media`
  - 作用：`X-Accel-Redirect` 使用的内部路径前缀

### 频率限制

- `MUSIC_SHARE_LOGIN_RATE_LIMIT`
  - 默认：`10`
- `MUSIC_SHARE_LOGIN_RATE_WINDOW_SECONDS`
  - 默认：`60`
- `MUSIC_SHARE_UPLOAD_RATE_LIMIT`
  - 默认：`30`
- `MUSIC_SHARE_UPLOAD_RATE_WINDOW_SECONDS`
  - 默认：`3600`
- `MUSIC_SHARE_PUBLIC_RATE_LIMIT`
  - 默认：`240`
- `MUSIC_SHARE_PUBLIC_RATE_WINDOW_SECONDS`
  - 默认：`60`

## MIME 与字段限制

### 允许的音频 MIME

- `audio/aac`
- `audio/mp4`
- `audio/mpeg`
- `audio/ogg`
- `audio/x-m4a`

### 允许的图片 MIME

- `image/jpeg`
- `image/png`
- `image/webp`

### 文本字段长度

以下字段最大长度均为 `256`：

- `title`
- `artist`
- `album`

## 存储与清理机制

每个分享默认占用一个独立目录：

```text
storage/<uuid>/
  audio.ogg
  cover.jpg
  meta.json
```

SQLite 保存分享记录和 session。

清理任务默认每 `60` 秒扫描一次，删除：

- `expires_at` 已到期的分享
- 已主动终止的分享
- 已过期的 session

删除时会一并清除：

- 数据库记录
- 音频文件
- 封面文件
- `storage/<uuid>/` 目录

## Nginx 配置

推荐部署方式是让 Nginx 和应用共享 `storage` 目录，并由 Nginx 处理真正的文件输出。

示例配置见：

- `nginx.example.conf`

核心思路：

```nginx
location /internal-media/ {
    internal;
    alias /srv/music-share/backend/data/storage/;
}

location / {
    proxy_pass http://127.0.0.1:2087;
}
```

注意：

- `alias` 必须指向后端的真实 `storage` 目录
- `internal` 路径不能直接暴露给公网
- 如果公网和内网域名不同，务必设置：
  - `MUSIC_SHARE_PUBLIC_API_BASE_URL`
  - `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

## 开发环境示例

```bash
cd backend
export MUSIC_SHARE_USER_PASSWORD='user-pass'
export MUSIC_SHARE_ADMIN_PASSWORD='admin-pass'
export MUSIC_SHARE_PUBLIC_API_BASE_URL='http://127.0.0.1:2087'
export MUSIC_SHARE_PUBLIC_SHARE_BASE_URL='http://127.0.0.1:5173'
export MUSIC_SHARE_CORS_ALLOWED_ORIGINS='http://127.0.0.1:5173'
python3 -m app
```

这个例子下：

- Android 上传得到的 `track_url` / `stream_url` / `cover_url` 指向 `127.0.0.1:2087`
- `share_url` 指向 Web 播放器前端 `127.0.0.1:5173/<share_code>`

## 常见问题

### 为什么上传成功后返回的 `share_url` 不对？

因为默认会根据当前请求的 `Host` 生成 URL。若 Android 是通过局域网地址访问后端，而分享页实际要走公网域名，就必须设置：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`

### 为什么 `GET /stream/{share_code}` 只看到 `X-Accel-Redirect`？

这是正常行为。默认模式下，应用只负责鉴权和状态判断，真实音频文件由 Nginx 读取并输出。

如果你在本地不想配 Nginx，可设置：

```bash
export MUSIC_SHARE_USE_X_ACCEL_REDIRECT=false
```

### 为什么公开接口不返回 `uuid`？

`uuid` 是内部存储主键，不应该暴露给公开页面。公开访问统一走：

- `share_code`

### 为什么 `cover_url` 有时是 `null`？

上传时如果没有提供封面，返回结果中的 `cover_url` 就会是 `null`，此时前端应做默认占位图降级。

## 测试

运行测试：

```bash
cd backend
pytest
```

当前测试覆盖：

- 登录
- 上传
- 公开查询
- 客户端终止
- 管理员终止
- 清理任务
