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

# 3. 自签证书（域名按实际修改；有正式证书直接放 certs/ 即可）
mkdir -p certs
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

```bash
cd ~/wrong-notebook
git checkout runtime && git merge main
npm ci --registry=https://registry.npmjs.org   # 依赖有变化时；之后重做 Prisma 引擎替换
npx prisma generate
PRISMA_SCHEMA_ENGINE_BINARY=$PWD/node_modules/@prisma/engines/schema-engine npx prisma migrate deploy
npm run build
cp -r public .next/standalone/public
cp -r .next/static .next/standalone/.next/static
for d in prisma config certs; do rm -rf ".next/standalone/$d"; ln -sfn "$PWD/$d" ".next/standalone/$d"; done
systemctl --user restart wrong-notebook wrong-notebook-https
curl -sk https://wn.example.ltd/api/health
```

## 排障

- 日志：`journalctl --user -u wrong-notebook -u wrong-notebook-https -f`
- 健康检查：`curl -sk https://域名/api/health`，返回 `{"status":"ok",...}`
- 代理 502 / socket hang up：确认 `https-server.js` 包含「req complete 才销毁」的修复（回归测试 `node --test tests/https-server.test.mjs`）
- 首次访问证书警告：自签证书正常现象，点「高级 → 继续」，或把 `certs/cert.pem` 导入设备受信任根证书
