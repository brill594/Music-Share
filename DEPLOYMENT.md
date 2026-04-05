# Music Share 部署说明

- `backend/` 部署到 Cloudflare Workers
- `web-player/` 部署到 Cloudflare Pages
- `D1` 保存分享和会话数据
- `R2` 保存音频与封面对象

## 先理解 4 类配置

### 1. `wrangler.toml`

这是 Cloudflare Worker 的部署配置文件，放在 [backend/wrangler.toml](/Users/brilliant/repo/Music%20Share_Worker/backend/wrangler.toml)。

这里通常放：

- Worker 名称
- D1 binding
- R2 binding
- Cron 配置
- 一些默认的非敏感配置

这里不该放：

- 密码
- Cloudflare API token

### 2. GitHub Repository Secrets

这是 GitHub 仓库级别的 secret，整个仓库的 workflow 都能用。

适合放：

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

如果你现在只是想先跑通自动部署，初学者最推荐直接用 `Repository Secrets`。

### 3. GitHub Environment Secrets

这是 GitHub Environment 级别的 secret，例如你可以建一个 `production` environment，再把生产密钥放进去。

适合场景：

- 你想区分 `staging` 和 `production`
- 你想给生产部署加审批
- 你想把生产密钥和普通仓库级密钥分开

如果你还不熟 GitHub Actions，可以先不使用它，先只用 `Repository Secrets`。

注意：

- 这里的 `environment` 是 GitHub 的部署环境，不是 `.env` 文件
- 私有仓库能不能用 environment，取决于你的 GitHub 套餐

### 4. GitHub Variables

Variables 用来放非敏感配置。

适合放：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `VITE_API_BASE_URL`
- `CLOUDFLARE_PAGES_PROJECT_NAME`

不适合放：

- 密码
- token

## 初学者推荐方案

如果你是第一次部署，建议直接这样分：

- `wrangler.toml`
  - D1 / R2 binding
  - Cron
- `Repository Secrets`
  - `CLOUDFLARE_API_TOKEN`
  - `CLOUDFLARE_ACCOUNT_ID`
  - `MUSIC_SHARE_USER_PASSWORD`
  - `MUSIC_SHARE_ADMIN_PASSWORD`
- `Repository Variables`
  - `MUSIC_SHARE_PUBLIC_API_BASE_URL`
  - `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
  - `CLOUDFLARE_PAGES_PROJECT_NAME`
  - `VITE_API_BASE_URL`

如果你以后想升级，再把生产密钥迁到 `Environment Secrets`。

## 部署前你要准备什么

至少准备这些：

- 一个 Cloudflare 账号
- 一个 D1 数据库
- 一个 R2 bucket
- 一个 Cloudflare Pages 项目
- 一个后端域名，例如 `api.example.com`
- 一个前端域名，例如 `share.example.com`
- 一个 GitHub 仓库

推荐域名拆分：

- `api.example.com` 给 Worker
- `share.example.com` 或 `music.example.com` 给 Pages

## 第一步：创建 Cloudflare 资源

### 1. 创建 D1

可以在 Cloudflare 控制台创建，也可以用 Wrangler：

```bash
cd backend
npx wrangler d1 create music-share-backend
```

创建后会拿到两类值：

- `database_name`
- `database_id`

这里的 `database_id` 就是 D1 的 UUID。

### 2. 创建 R2

```bash
cd backend
npx wrangler r2 bucket create music-share-backend
```

R2 这里主要需要：

- `bucket_name`

### 3. 创建 Pages 项目

在 Cloudflare Pages 控制台创建一个项目，项目名例如：

- `music-share-web-player`

后面 GitHub Actions 会用到这个项目名。

如果你是在 Cloudflare Pages 控制台里手动创建项目，界面上通常会让你填写这些项：

- 项目名称：
  - `music-share-web-player`
- 生产分支：
  - `cloud`
  - 如果你准备以后用 `main` 发布正式版，那这里就填 `main`
- 框架预设：
  - `无`
- 构建命令：
  - `npm run build`
- 构建输出目录：
  - `dist`

如果界面里还有“根目录 / Root directory”，这个项目应填：

- `web-player`

如果界面里有环境变量，至少填：

- `VITE_API_BASE_URL=https://api.example.com/`

注意两点：

1. 这里的“项目名称”要和 GitHub Variable `CLOUDFLARE_PAGES_PROJECT_NAME` 一致。
2. 如果你已经使用本仓库的 GitHub Actions 通过 `wrangler pages deploy` 直传部署，那么 Cloudflare 控制台里的构建命令和输出目录主要是“创建项目时要填的基础信息”，后续正式部署通常由 GitHub Actions 里的构建结果接管。

## 第二步：填写 `wrangler.toml`

把 D1 / R2 的 binding 写进 [backend/wrangler.toml](/Users/brilliant/repo/Music%20Share_Worker/backend/wrangler.toml)。

你至少要改这些值：

- `[[d1_databases]].database_id`
- `[[d1_databases]].database_name`
- `[[r2_buckets]].bucket_name`

这些值保存在文件里是正常的，因为它们属于部署绑定，不是密码。

换句话说：

- `database_id`、`bucket_name` 可以进仓库
- `password`、`token` 不应该进仓库

## 第三步：在 GitHub 里配置 Secrets 和 Variables

路径：

- `Settings`
- `Secrets and variables`
- `Actions`

### 1. Repository Secrets

至少添加：

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `MUSIC_SHARE_USER_PASSWORD`
- `MUSIC_SHARE_ADMIN_PASSWORD`

其中 `CLOUDFLARE_API_TOKEN` 不建议直接给过大的权限。按当前仓库的两个 workflow，建议至少授予这些 Account 级权限：

- `Workers Scripts Edit`
- `D1 Edit`
- `Workers R2 Storage Edit`
- `Cloudflare Pages Edit`

如果你想更方便排查问题，也可以额外给只读权限：

- `Workers Scripts Read`
- `D1 Read`
- `Workers R2 Storage Read`
- `Cloudflare Pages Read`

一般不需要额外给：

- `Zone DNS Edit`
- `SSL and Certificates Edit`
- `Workers Routes Edit`

前提是你已经在 Cloudflare 控制台里提前创建并配置好了：

- D1
- R2
- Pages 项目
- 自定义域名和 DNS

这样 GitHub Actions 只负责“部署代码”，不负责“改域名配置”。

### 2. Repository Variables

至少添加：

- `MUSIC_SHARE_PUBLIC_API_BASE_URL`
- `MUSIC_SHARE_PUBLIC_SHARE_BASE_URL`
- `CLOUDFLARE_PAGES_PROJECT_NAME`
- `VITE_API_BASE_URL`

一个常见示例：

```text
MUSIC_SHARE_PUBLIC_API_BASE_URL=https://api.example.com
MUSIC_SHARE_PUBLIC_SHARE_BASE_URL=https://share.example.com
CLOUDFLARE_PAGES_PROJECT_NAME=music-share-web-player
VITE_API_BASE_URL=https://api.example.com/
```

### 3. 如果你想用 Environment Secrets

这不是必须的。

如果你以后要给生产环境加保护，可以：

1. 在 GitHub 创建一个 `production` environment
2. 把生产 secrets 放进去
3. 给 environment 配审批规则

但对第一次部署来说，不需要先做这一步。


## 第四步：启用 GitHub Actions 自动部署

仓库里已经有两个 workflow：

- 后端 Worker 部署：
  - [.github/workflows/deploy-backend.yml](/Users/brilliant/repo/Music%20Share_Worker/.github/workflows/deploy-backend.yml)
- 前端 Pages 部署：
  - [.github/workflows/deploy-web-player.yml](/Users/brilliant/repo/Music%20Share_Worker/.github/workflows/deploy-web-player.yml)

### 后端 workflow 会做什么

它会：

1. `npm ci`
2. `npm run typecheck`
3. `npm test`
4. `wrangler d1 migrations apply MUSIC_SHARE_DB --yes`
5. `wrangler deploy`

默认触发：

- push 到 `main`
- push 到 `cloud`
- 手动触发 `workflow_dispatch`

### 前端 workflow 会做什么

它会：

1. `npm ci`
2. `npm run typecheck`
3. `npm run build`
4. `wrangler pages deploy dist ...`

默认触发：

- push 到 `main`
- push 到 `cloud`
- 对 `main/cloud` 的 pull request
- 手动触发 `workflow_dispatch`

说明：

- 同仓库 PR 会尝试做 Pages preview
- fork PR 只跑构建检查，不会拿你的 Cloudflare secrets 去部署

## 第五步：后端部署

### 1. 本地先验证一次

```bash
cd backend
npm install
npm run typecheck
npm test
```

### 2. 本地执行一次迁移也可以

本地开发数据库：

```bash
cd backend
npx wrangler d1 migrations apply MUSIC_SHARE_DB --local
```

正式环境迁移通常会由 GitHub Actions 自动执行。

### 3. 推送代码触发部署

把改动 push 到 `main` 或 `cloud`，后端 workflow 会自动部署。

如果你不想等自动触发，也可以在 GitHub Actions 页面手动运行。

### 4. 后端部署完成后检查

至少检查：

- Worker 自定义域名是否生效
- `POST /auth/login` 能正常返回
- `POST /upload` 能写入 D1 和 R2
- `GET /track/{share_code}` 能返回元数据
- `GET /stream/{share_code}` 能返回音频
- `GET /cover/{share_code}` 行为正确
- `track_url`、`stream_url`、`cover_url`、`share_url` 的域名正确

## 第六步：前端部署

### 1. 本地先验证一次

```bash
cd web-player
npm install
npm run typecheck
npm run build
```

### 2. 确认 `VITE_API_BASE_URL`

GitHub Variable `VITE_API_BASE_URL` 必须指向正式后端域名，例如：

```text
https://api.example.com/
```

如果你在 Cloudflare Pages 控制台也配置了同名环境变量，建议和 GitHub Variable 保持一致。

### 3. 推送代码触发部署

把改动 push 到 `main` 或 `cloud`，前端 workflow 会自动部署到 Pages。

### 4. 前端部署完成后检查

至少检查：

- 直接打开 `/{shareCode}` 能进页面
- 刷新 `/{shareCode}` 不会变成静态 404
- `404` 的分享能进入 Not Found 页面
- `410` 的分享能进入 Expired 页面
- 音频对象读取失败时页面提示错误，而不是误判成分享不存在

## 第七步：设置自定义域名

**在worker和pages分别设置自定义域名与之前输入的相对应**

---

## 推荐部署顺序

第一次上线时，建议按这个顺序：

1. 先创建 D1、R2、Pages 项目
2. 再修改 `backend/wrangler.toml`
3. 再配置 GitHub Repository Secrets / Variables
4. 先部署后端 Worker
5. 用真实域名验证后端接口
6. 再部署前端 Pages
7. 最后用真实分享链接做一次端到端验证

## HTTPS 怎么办

这套架构不需要你自己配 Nginx 或 Certbot。

- Worker 绑定自定义域名时，Cloudflare 会处理 HTTPS
- Pages 绑定自定义域名时，Cloudflare 也会处理 HTTPS

你通常只需要在 Cloudflare 控制台把：

- `api.example.com`
- `share.example.com`

分别绑定到 Worker 和 Pages。

## 常见问题

### 1. 为什么 `database_id` 写在文件里，不放 secret？

因为它是资源绑定配置，不是密码。

它告诉 Cloudflare：

- 这个 Worker 应该绑定哪个 D1

而不是给外部访问数据库的通用凭据。

### 2. fork 我仓库的人会直接拿到我的 D1 / R2 吗？

不会。

fork 的人能看到你的 `wrangler.toml`，但他们不能因为看到了 `database_id` 或 `bucket_name` 就直接访问你的资源。

真正敏感的是：

- `CLOUDFLARE_API_TOKEN`
- 用户密码
- 管理员密码

这些必须放在 GitHub Secrets 或 Cloudflare Worker secrets 中。

### 3. GitHub Actions 里的 secret 有两种，我应该用哪种？

如果你是第一次部署：

- 先用 `Repository Secrets`

如果你以后要区分生产环境：

- 再用 `Environment Secrets`

不要一开始就把问题复杂化。

### 4. private 仓库能不能跑 workflow？

能。

私有仓库也可以运行 GitHub Actions。区别主要在于：

- 你的套餐
- 可用分钟数
- 是否能用 Environment 功能

### 5. fork PR 会不会拿到我的 secrets？

默认不会。

这也是为什么当前前端 workflow 对 fork PR 只跑构建校验，不做真实部署。

## 进一步说明

详细模块说明见：

- [README.md](/Users/brilliant/repo/Music%20Share_Worker/README.md)
- [backend/README.md](/Users/brilliant/repo/Music%20Share_Worker/backend/README.md)
- [web-player/README.md](/Users/brilliant/repo/Music%20Share_Worker/web-player/README.md)
