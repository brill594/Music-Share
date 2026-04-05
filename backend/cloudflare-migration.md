# Backend 迁移路线规划（Cloudflare Workers + R2 + D1）

## 目标

将 `backend` 从“FastAPI + SQLite + 本地文件系统 + Nginx X-Accel-Redirect”迁移为“Cloudflare Worker + D1 + R2 + Cron Trigger”，同时尽量保持现有公开接口与上传流程稳定。

目标平台：

- 计算层：Cloudflare Workers
- 数据库：D1
- 对象存储：R2
- 定时清理：Workers Cron Triggers

## 当前实现与迁移影响点

当前后端的 Cloudflare 迁移关键点主要分布在这些文件：

- `app/main.py`
  - 定义全部 API 路由与认证、上传、公开访问逻辑
- `app/database.py`
  - 直接操作 SQLite 文件
- `app/services.py`
  - 包含认证、文件落盘、`X-Accel-Redirect`、内存限流、定时清理
- `app/config.py`
  - 依赖本地路径、运行时密码文件和本地存储目录

这四类能力在 Cloudflare 上都要改：

1. SQLite 文件改为 D1。
2. 本地文件目录改为 R2。
3. `X-Accel-Redirect` 改为 Worker 直接从 R2 返回对象。
4. 进程内循环清理改为 Cron Trigger。

## 推荐目标架构

推荐把后端视为一次“运行时迁移”，而不是机械端口转换。

建议目标结构：

- `backend/src/index.ts`
  - Worker 入口
- `backend/src/routes/*`
  - 公开接口、客户端接口、管理接口
- `backend/src/lib/*`
  - 认证、D1 查询、R2 访问、响应序列化
- `backend/migrations/*.sql`
  - D1 schema 迁移
- `backend/wrangler.toml`
  - D1、R2、Cron 绑定

语言建议：

- 推荐将 Worker 实现切到 TypeScript

原因不是“Python 不能跑”，而是当前 Cloudflare 官方文档、Wrangler、D1/R2 绑定示例与本地开发链路都明显更偏向 TypeScript/JavaScript。对这个迁移项目而言，TypeScript 的实现成本和维护成本都更低。

## 关键设计决策

### 1. D1 保持与当前 SQLite 模型接近

首阶段建议保持 schema 近似一比一映射，避免迁移时同时做数据模型重构。

建议首阶段保留表：

- `shares`
- `sessions`

建议首阶段保留索引语义：

- `share_code` 唯一
- `client_install_id, created_at`
- `expires_at`
- `sessions.expires_at`

结论：

- 先迁基础设施，再重构字段
- `status` 字段首阶段可以继续保留，以降低接口改动风险

### 2. R2 不能直接替代“公开静态文件桶”

当前业务要求：

- 分享可过期
- 分享可提前终止
- 音频和封面访问受分享状态控制

因此不建议首阶段把音频或封面直接做成永久公开 URL。

推荐做法：

- R2 使用私有 bucket
- `GET /stream/{share_code}` 和 `GET /cover/{share_code}` 仍经 Worker 校验分享状态
- Worker 校验通过后再从 R2 读取对象并返回

这相当于用 Worker 替代现在的 `FastAPI + Nginx internal` 组合。

### 3. 上传流程分两阶段迁移

当前配置里：

- 音频最大 `64 MiB`
- 封面最大 `8 MiB`

按照 Cloudflare 当前 Workers 请求体限制，Free/Pro 计划默认是 `100 MB`。所以首阶段仍可以保留“客户端上传到 API，由 API 写入 R2”的模式，以减少 Android 或其他客户端改动。

但这只适合首阶段。

中期建议升级为：

1. Worker 生成 R2 预签名上传 URL
2. 客户端直接上传到 R2
3. 客户端再调用“完成上传”接口写入 D1

这样能规避请求体大小瓶颈，并提升大文件上传稳定性。

### 4. 当前内存限流方案不能直接照搬

`services.py` 里的 `RateLimiter` 是进程内内存桶。在 Workers 多实例、多地域环境里，这种方案不再可靠。

迁移建议：

- 边缘层粗限流：
  - 使用 Cloudflare WAF / Rate Limiting 规则保护 `/auth/login`、`/upload`、`/track`、`/stream`
- 应用层细粒度控制：
  - 只保留参数校验、权限校验和对象状态校验

在不额外引入 KV / Durable Object 的前提下，不建议把当前内存限流逻辑硬搬到 Worker。

### 5. 清理任务改为 Cron Trigger

当前 `CleanupService` 每 60 秒清理一次。

迁移后建议：

- 使用 Worker `scheduled()` 处理器
- 以 1 分钟 cron 触发清理
- 每次执行：
  - 查询 D1 中已过期或已终止的分享
  - 删除对应 R2 对象
  - 删除 D1 记录
  - 清理过期 session

注意：

- Cron Trigger 以 UTC 触发
- 分钟级粒度足够覆盖当前 60 秒清理模型

## 分阶段路线

### 阶段 0：冻结接口与对象键设计

先不要急着写 Worker，先锁定这些内容：

- 公开接口路径是否保持原样：
  - `/track/{share_code}`
  - `/stream/{share_code}`
  - `/cover/{share_code}`
- 客户端与管理接口是否保持原样
- R2 对象键命名规则

推荐对象键：

- `shares/{uuid}/audio.<ext>`
- `shares/{uuid}/cover.<ext>`

建议此阶段顺便决定：

- 是否继续保留 `meta.json`

建议答案：

- 不再把 `meta.json` 作为主数据源
- D1 成为唯一主数据源
- 如需排障，可后续再增加对象元数据或审计日志

### 阶段 1：在 `backend` 目录建立 Worker 骨架

这一阶段不迁数据，只建立新运行时骨架。

建议落地项：

- 新建 `wrangler.toml`
- 新建 `src/index.ts`
- 新建 `src/lib/env.ts`
- 新建 `src/lib/db.ts`
- 新建 `src/lib/storage.ts`
- 新建 `migrations/0001_init.sql`

建议在 `wrangler.toml` 中配置：

- `d1_databases`
- `r2_buckets`
- `triggers.crons`

阶段目标：

- 本地 `wrangler dev` 可以启动
- `D1` 和 `R2` 绑定已经连通

### 阶段 2：先迁公开读取接口

优先迁移这三个接口：

- `GET /track/{share_code}`
- `GET /stream/{share_code}`
- `GET /cover/{share_code}`

原因：

- 这是 `web-player` 的唯一关键依赖
- 不涉及上传和后台管理，最适合作为第一批切换目标

迁移要求：

- `track` 返回结构尽量与现在一致
- `stream` 改为 Worker 从 R2 读对象后返回
- `cover` 同理
- `404` 与 `410` 语义保持一致

阶段收益：

- 前端可以先与 Worker 公开接口联调
- 迁移路径从“全量替换”变成“先读后写”

### 阶段 3：迁 D1 schema 与现有 SQLite 数据

先把当前 SQLite 表结构迁到 D1。

建议步骤：

1. 把 `database.py` 中的建表 SQL 改写为 `migrations/*.sql`
2. 本地创建 D1 数据库并执行迁移
3. 从现有 SQLite 导出 SQL
4. 将 SQL 清洗为 D1 可执行格式
5. 用 Wrangler 导入到 D1

注意事项：

- D1 支持导入现有 SQLite 数据，但导入入口是 SQL，不是直接上传原始 `.sqlite3` 文件
- 导入前要确认时间字段、唯一索引和外部路径字段与新 schema 一致

阶段目标：

- Worker 能从 D1 正确查询已有分享记录和 session

### 阶段 4：迁 R2 对象与路径语义

这一阶段把 `backend/data/storage` 迁到 R2。

建议步骤：

1. 将现有目录结构映射到 R2 key
2. 迁移已有音频与封面对象
3. 在 D1 中确认 `audio_path`、`cover_path` 或新对象键字段可正确定位对象
4. 用真实分享链接验证：
   - 元数据可查
   - 音频可下
   - 封面可取

设计建议：

- 可以沿用当前 `audio_path`、`cover_path` 字段语义
- 只是它们从“文件系统相对路径”变为“R2 object key”

这样可以降低序列化层和返回模型的改动范围。

### 阶段 5：迁认证、管理与上传接口

当公开读取链路稳定后，再迁这些接口：

- `POST /auth/login`
- `POST /upload`
- `GET /client/shares`
- `GET /client/shares/{share_code}`
- `POST /client/shares/{share_code}/terminate`
- `GET /admin/tracks`
- `POST /admin/tracks/{share_code}/terminate`

建议顺序：

1. 登录与 session 读取
2. 客户端/管理员列表与终止能力
3. 上传

原因：

- 上传最容易受请求体大小、对象写入、失败补偿影响
- 应放在读取链路稳定之后

### 阶段 6：将上传改造为 R2 直传

这不是首阶段必须做，但从长期看应当纳入路线。

推荐接口拆分：

- `POST /upload/init`
  - 返回分享 `uuid`
  - 返回音频和封面的 R2 预签名上传 URL
- 客户端直传到 R2
- `POST /upload/complete`
  - Worker 校验对象存在、写入 D1、生成分享地址

好处：

- 避免 Worker 承载完整文件传输
- 规避 100 MB 请求体上限
- 上传失败重试更清晰

### 阶段 7：切流与回滚

推荐切流方式：

1. 先把 Pages 前端切到新的公开读取 Worker
2. 再切客户端管理接口
3. 最后切上传链路

推荐回滚方式：

- 保留旧 FastAPI 服务一段观察期
- 回滚优先通过域名路由切换，而不是立即删旧数据
- 切流初期禁止同时改数据库 schema 和上传协议

## 建议的目录内迁移顺序

建议在 `backend` 内按这个顺序推进：

1. 新增 `wrangler.toml`
2. 新增 `migrations/`
3. 新增 Worker 读取链路
4. 打通 D1
5. 打通 R2
6. 迁公开接口
7. 迁认证与管理
8. 最后改上传

## 风险清单

- 当前 `X-Accel-Redirect` 语义不存在于 Cloudflare，需要 Worker 自己返回对象流和头信息。
- 当前内存限流在 Workers 环境不成立，必须改为平台级限流或新状态存储方案。
- 当前清理逻辑依赖进程内后台任务，迁移后必须由 Cron Trigger 接管。
- 当前 SQLite 可直接本地读写，D1 导入时需要先转 SQL。
- 如果后续音频大小超过 Workers 请求体限制，`POST /upload` 直传 API 会成为瓶颈。

## 验收标准

- 公开分享查询、音频下载、封面读取全部跑在 Worker + D1 + R2 上。
- 已过期和已终止分享在 Worker 上仍返回正确状态。
- 定时清理能删除 D1 记录和 R2 对象。
- 客户端与管理接口行为与当前语义一致。
- 在保留旧服务的前提下，可分阶段切流和回滚。

## 决策建议

后端迁移最重要的不是“把 FastAPI 改写成 Worker”，而是先把系统边界改成 Cloudflare 适合的样子：

- D1 负责结构化状态
- R2 负责对象
- Worker 负责鉴权与状态校验
- Cron 负责回收

只要边界改对，后面的路由移植和客户端切换都会顺很多。

## 参考资料

- Workers Limits:
  - https://developers.cloudflare.com/workers/platform/limits/
- D1 Migrations:
  - https://developers.cloudflare.com/d1/reference/migrations/
- D1 Querying / SQLite 兼容说明:
  - https://developers.cloudflare.com/d1/best-practices/query-d1/
- R2 Upload Objects:
  - https://developers.cloudflare.com/r2/objects/multipart-objects/
- Workers Cron Triggers:
  - https://developers.cloudflare.com/workers/configuration/cron-triggers/
