# 本地源码生产部署（systemd 用户服务 + 内置 HTTPS）

不用 Docker，在本机以生产模式长期运行：`main` 分支只同步上游，`runtime` 分支合入本地修改并用于跑服务。

## 分支约定

| 分支 | 用途 |
| --- | --- |
| `main` | 永远快进同步上游（`git merge --ff-only upstream/main`），不直接承载本地修改 |
| `feature/*` | 本地功能开发 |
| `runtime` | 运行分支：合并 `main` 与本地功能分支，服务始终从此分支构建运行 |

更新流程：

```bash
git checkout main && git fetch upstream && git merge --ff-only upstream/main
git checkout runtime && git merge main && git merge feature/xxx   # 按需
# 然后按下文「更新与重启」重建
```

## 目录与数据

服务跑在 `.next/standalone/`（Next.js standalone 产物），其中以下目录用符号链接指回仓库，构建后需要重建链接：

| standalone 内路径 | 指向 | 内容 |
| --- | --- | --- |
| `prisma/` | 仓库 `prisma/` | SQLite 数据库 `dev.db` 与迁移 |
| `config/` | 仓库 `config/` | 网页保存的 AI 配置 |
| `certs/` | 仓库 `certs/` | HTTPS 证书 `cert.pem` / `key.pem`（`.gitignore` 已忽略 `*.pem`） |

## 首次部署

```bash
# 1. 依赖与 Prisma 引擎（Ubuntu 26.10 等 OpenSSL 3+ 系统需要 openssl-3.0.x 引擎，
#    并安装 libssl3t64；npm ci 后需重新替换，见下方说明）
npm ci --registry=https://registry.npmjs.org
npx prisma generate

# 2. 数据库迁移
PRISMA_SCHEMA_ENGINE_BINARY=$PWD/node_modules/@prisma/engines/schema-engine npx prisma migrate deploy

# 3. 证书：域名托管在 Cloudflare 时用 acme.sh + DNS-01 申请 Let's Encrypt（内网 IP 也可用，
#    需要 Zone/DNS/Edit 权限的 API Token）；没有域名 DNS 控制权时退回自签
mkdir -p certs
# 方式 A：Let's Encrypt（推荐，自动续期）
# curl 安装 acme.sh 后：
# export CF_Token="<Cloudflare API Token>"
# ~/.acme.sh/acme.sh --issue --dns dns_cf -d wn.example.ltd --server letsencrypt
# ~/.acme.sh/acme.sh --install-cert -d wn.example.ltd --ecc \
#   --key-file ~/wrong-notebook/certs/key.pem \
#   --fullchain-file ~/wrong-notebook/certs/cert.pem \
#   --reloadcmd "systemctl --user restart wrong-notebook-https"
# ~/.acme.sh/acme.sh --install-cronjob
# 方式 B：自签（浏览器首次访问需手动信任）
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout certs/key.pem -out certs/cert.pem \
  -subj "/CN=wn.example.ltd" \
  -addext "subjectAltName=DNS:wn.example.ltd"

# 4. .env 设置访问地址
# NEXTAUTH_URL="https://wn.example.ltd"
# AUTH_TRUST_HOST=true

# 5. 生产构建并布置 standalone
npm run build
cp -r public .next/standalone/public
cp -r .next/static .next/standalone/.next/static
for d in prisma config certs; do
  rm -rf ".next/standalone/$d"
  ln -sfn "$PWD/$d" ".next/standalone/$d"
done

# 6. systemd 用户服务（见下节），443 端口需要授权（见「443 端口」）
```

### Prisma 引擎（OpenSSL 3+ 系统）

Prisma 5 检测不到新系统 OpenSSL 时回退 1.1 引擎导致 `libssl.so.1.1` 缺失。处理：

```bash
# 系统包（与 OpenSSL 4 并存）：sudo apt-get install -y libssl3t64
cd node_modules/@prisma/engines
HASH=$(node -p "require('@prisma/engines-version').enginesVersion")
BASE="https://binaries.prisma.sh/all_commits/$HASH/debian-openssl-3.0.x"
curl -fsSL -o schema-engine.gz "$BASE/schema-engine.gz" && gunzip -f schema-engine.gz && chmod +x schema-engine
curl -fsSL -o q.gz "$BASE/libquery_engine.so.node.gz" && gunzip -f q.gz
mv q libquery_engine-debian-openssl-3.0.x.so.node 2>/dev/null || mv q.so.node libquery_engine-debian-openssl-3.0.x.so.node
chmod +x libquery_engine-debian-openssl-3.0.x.so.node
cd ../../.. && npx prisma generate   # generate 会把 3.0 引擎一并复制进 .prisma/client
```

每次 `npm ci` 后需重做本节。

## systemd 用户服务

`~/.config/systemd/user/wrong-notebook.service`：

```ini
[Unit]
Description=Wrong Notebook (standalone)
After=network-online.target

[Service]
WorkingDirectory=%h/wrong-notebook/.next/standalone
Environment=HOSTNAME=127.0.0.1
Environment=PORT=3000
ExecStart=/usr/bin/node server.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

`~/.config/systemd/user/wrong-notebook-https.service`：

```ini
[Unit]
Description=Wrong Notebook HTTPS proxy
After=wrong-notebook.service
Requires=wrong-notebook.service

[Service]
WorkingDirectory=%h/wrong-notebook
Environment=CERT_DIR=%h/wrong-notebook/certs
Environment=PORT=3000
Environment=HTTPS_PORT=443
ExecStart=/usr/bin/node https-server.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

启用（`loginctl enable-linger` 需要管理员执行一次，保证未登录时也运行）：

```bash
systemctl --user daemon-reload
systemctl --user enable --now wrong-notebook wrong-notebook-https
loginctl enable-linger ray   # 需要权限时由管理员执行
```

### 443 端口

普通用户不能绑定 443（`net.ipv4.ip_unprivileged_port_start=1024`）。任选其一（都需要管理员）：

```bash
# 方案 A：允许用户从 443 起绑定（影响全机）
sudo sysctl net.ipv4.ip_unprivileged_port_start=443   # 持久化写 /etc/sysctl.d/

# 方案 B：只给 node 二进制授权（node 升级后需重做）
sudo setcap cap_net_bind_service=+ep /usr/bin/node
```

没有管理员权限时改用高位端口（如 `HTTPS_PORT=3443`），访问 `https://域名:3443`。

## 更新与重启

### 自动更新（推荐）

`scripts/deploy-local.sh` 完成「main 快进上游 → runtime 合并 → 依赖与迁移 → 构建 → 原子切换 → 重启 → 健康检查」全过程，
配合 systemd 定时器每天自动检查一次：

```ini
# ~/.config/systemd/user/wrong-notebook-update.service
[Unit]
Description=Wrong Notebook 自动更新（同步上游、构建、部署、重启）
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=%h/wrong-notebook
Environment=PATH=/usr/local/bin:/usr/bin:/bin
Environment=HOME=%h
ExecStart=%h/wrong-notebook/scripts/deploy-local.sh
```

```ini
# ~/.config/systemd/user/wrong-notebook-update.timer
[Unit]
Description=每天检查 Wrong Notebook 上游更新

[Timer]
OnCalendar=*-*-* 04:30:00
Persistent=true
RandomizedDelaySec=10min

[Install]
WantedBy=timers.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now wrong-notebook-update.timer
systemctl --user start wrong-notebook-update.service   # 立即检查一次
journalctl --user -u wrong-notebook-update -f          # 查看运行日志
```

行为说明：

- 版本号与提交号在**构建时**由 `git describe` / `git rev-parse` 注入产物（`next.config.ts` 的 `env`），
  部署后 `/api/health` 与「设置 → 关于」显示真实版本，不需要手工设置运行时环境变量
- 构建写在 `.next-staging`，成功后与 `.next` 原子切换并重启；**合并冲突、构建失败或健康检查失败都会保留旧版本继续运行**（切换后失败会自动回滚）
- 依赖锁文件变化时才 `npm ci`，随后自动补齐 Prisma OpenSSL 3.0 引擎
- 工作区有未提交改动时脚本会拒绝执行，避免把调试代码带上生产

手工控制：

```bash
scripts/deploy-local.sh              # 有更新才构建
scripts/deploy-local.sh --force      # 强制重建（如改了 .env 或系统库）
scripts/deploy-local.sh --no-merge   # 只构建部署，不碰 git
```

### 手工更新

不使用定时器时，等价操作：

```bash
cd ~/wrong-notebook
scripts/deploy-local.sh
```

## 排障

- 日志：`journalctl --user -u wrong-notebook -u wrong-notebook-https -f`
- 健康检查：`curl -sk https://域名/api/health`，返回 `{"status":"ok",...}`
- 代理 502 / socket hang up：确认 `https-server.js` 包含「req complete 才销毁」的修复（回归测试 `node --test tests/https-server.test.mjs`）
- 证书信任：Let's Encrypt 证书无需任何手动操作；自签证书首次访问点「高级 → 继续」，或把 `certs/cert.pem` 导入设备受信任根证书
