# Android App 路线图

## 模块目标

Android App 是整个项目的入口，负责：

- 从 Poweramp 获取当前播放曲目
- 将音频转码为适合小带宽传输的轻量格式
- 上传到后端并生成分享链接
- 管理本地配置、认证、分享状态和管理员入口

## 关键前提

1. Poweramp API 返回的 `path` 不能默认视为文件系统绝对路径。
2. App 不能继承 Poweramp 的存储权限，必须自己持有 SAF 权限。
3. 正确做法是将 Poweramp 的 `path` 转成当前 App 可访问的 `content://` `documentUri`。

## 1. 状态监听

实现目标：

- 接收 Poweramp 播放状态变更
- 提取当前曲目标识
- 将当前可分享曲目状态保存到本地

实现路线：

1. 使用 `BroadcastReceiver` 接入：
   - `com.maxmpz.audioplayer.TRACK_CHANGED`
   - `com.maxmpz.audioplayer.STATUS_CHANGED`
2. 同时兼容旧版 `track bundle` 和新版平铺 extras。
3. 从广播中提取：
   - `path`
   - `title`
   - `artist`
   - `album`
   - `duration`
   - `track id`
   - `updated_at`
4. 首次启动时引导用户通过 `ACTION_OPEN_DOCUMENT_TREE` 授权音乐目录。
5. 将 `path` 映射为 `documentUri`，并写入本地持久化存储。

建议数据模型：

- `poweramp_path`
- `document_uri`
- `title`
- `artist`
- `album`
- `duration_ms`
- `art_uri`
- `updated_at`
- `is_resolvable`

## 2. 触发机制

实现目标：

- 通过状态栏快捷入口一键发起分享

实现路线：

1. 实现 `TileService`。
2. 点击 Tile 后：
   - 读取本地最新曲目状态
   - 校验 `documentUri` 可访问性
   - 可用则进入转码和上传
3. 重逻辑放在 `ForegroundService` 或 `WorkManager` 中。

## 3. 音频处理与元数据提取

实现目标：

- 快速转码
- 保留必要元数据
- 支持可配置压缩参数

实现路线：

1. 集成 `ffmpeg-kit-audio`。
2. 读取源文件时基于 `content://` URI。
3. 默认输出：
   - 格式：`OGG`
   - 编码：`Opus`
4. 可选输出格式限制为小体积、适合低带宽传输：
   - `OGG Opus`
   - `OGG Vorbis`
   - `AAC / M4A`
   - `MP3`
5. 默认参数：
   - `96 kbps`
   - `44100 Hz`
   - 双声道
6. 可配置项：
   - `output_format`
   - `audio_codec`
   - `bitrate_kbps`
   - `sample_rate_hz`
   - `channels`
   - `loudness_mode`
   - `max_duration_seconds`
   - `max_output_size_mb`

推荐预设：

- `Fast Share`
- `Balanced`
- `Better Quality`

## 4. 认证、上传与分享管理

实现目标：

- 向后端可靠上传
- 自动获取和复用短期认证凭证
- 管理自己的分享状态

实现路线：

1. 使用 `Retrofit` 或 `OkHttp` 封装请求。
2. 首次安装后生成并持久化 `client_install_id`。
3. 设置中允许配置：
   - `base_url`
   - `auth_mode`
   - `basic_auth_password`
   - 管理员密码
4. 所有密码默认隐藏，支持“显示密码 / 隐藏密码”切换。
5. 当使用密码式认证时：
   - 只输入密码，不输入用户名
   - 通过统一认证接口请求短期 `cookie` 或 `api key`
   - 后端自动判断角色是 `user` 还是 `admin`
6. 本地维护 `auth_log`：
   - `last_auth_requested_at`
   - `last_auth_succeeded_at`
   - `last_role`
   - `last_auth_type`
   - `last_expires_at`
7. 每次打开 App 时：
   - 先根据 `auth_log` 判断凭证是否过期
   - 未过期则直接复用
   - 已过期或缺失则重新认证
8. 上传时附带：
   - 转码后的音频
   - 基础元数据
   - 期望分享有效时长

客户端功能：

- `我的分享`
  - 查看自己创建的分享
  - 查看剩余有效期
  - 提前结束分享
- `后端管理`
  - 仅管理员可见
  - 查看后端当前音乐
  - 提前结束任意分享

## 5. 配置管理、备份与导入

实现目标：

- 所有设置本地持久化
- 支持导出和导入 JSON 配置

必须持久化并导出：

- `client_install_id`
- `base_url`
- `auth_mode`
- `basic_auth_password`
- 管理员密码
- `session`
- `auth_log`
- 默认分享有效时长
- 转码参数
- 其他已持久化 UI 配置

建议 JSON 结构：

```json
{
  "schema_version": 1,
  "client_install_id": "opaque-random-install-id",
  "share_defaults": {
    "expire_after_seconds": 86400
  },
  "server": {
    "base_url": "https://music.example.com",
    "auth_mode": "none",
    "basic_auth_password": ""
  },
  "admin": {
    "enabled": false,
    "password": ""
  },
  "session": {
    "auth_type": "cookie",
    "access_key": "",
    "expires_at": ""
  },
  "auth_log": {
    "last_auth_requested_at": "",
    "last_auth_succeeded_at": "",
    "last_role": "user",
    "last_auth_type": "cookie",
    "last_expires_at": ""
  },
  "transcode": {
    "output_format": "ogg",
    "audio_codec": "opus",
    "bitrate_kbps": 96,
    "sample_rate_hz": 44100,
    "channels": 2
  }
}
```

## 6. 依赖后端约定

Android 端依赖后端提供：

- `POST /auth/login`
- `POST /upload`
- `GET /client/shares`
- `GET /client/shares/{share_code}`
- `POST /client/shares/{share_code}/terminate`
- `GET /admin/tracks`
- `POST /admin/tracks/{share_code}/terminate`

## 7. 验收标准

- 能从 Poweramp 获取当前曲目并解析为 `documentUri`
- 能通过 Tile 一键触发分享
- 能转码为轻量音频格式
- 能根据本地请求日志复用未过期的短期凭证
- 能上传并返回分享链接
- 能导出和导入完整 JSON 配置
- 管理员认证成功后可进入后端管理页
