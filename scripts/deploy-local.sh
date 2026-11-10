#!/usr/bin/env bash
# 本地源码部署：main 快进上游 → runtime 合入本地修改 → 迁移 → 构建到 .next-staging → 原子切换 → 重启服务。
# 供 systemd 定时器（wrong-notebook-update.timer）无人值守调用，也可手工执行。
#
# 用法：
#   scripts/deploy-local.sh            有更新才构建
#   scripts/deploy-local.sh --force    即使没有新提交也重新构建
#   scripts/deploy-local.sh --no-merge 只做「构建 + 切换 + 重启」（调试构建用）
#
# 安全保证：
#   - 合并冲突时中止，运行中的旧版本不受影响
#   - 构建失败时中止，旧版本继续服务
#   - 切换后健康检查失败时自动回滚到上一版本
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"

force=false
merge=true
for arg in "$@"; do
    case "$arg" in
        --force) force=true ;;
        --no-merge) merge=false ;;
        *) echo "未知参数：$arg" >&2; exit 2 ;;
    esac
done

log() { echo "[deploy $(date '+%F %T')] $*"; }
die() { echo "[deploy $(date '+%F %T')] 失败：$*" >&2; exit 1; }

dist=.next
staging=.next-staging
backup=.next-previous
# 部署清单：记录上次部署的提交与依赖锁，用于判断是否需要重建
stamp="$repo/.deploy-stamp"
services=(wrong-notebook wrong-notebook-https)

# 1) 同步代码
if $merge; then
    current=$(git rev-parse --abbrev-ref HEAD)
    [[ $current == runtime ]] || die "当前分支是 $current，自动部署要求停在 runtime"
    [[ -z $(git status --porcelain) ]] || die "工作区有未提交改动，跳过自动部署"

    log "同步上游"
    git fetch --prune upstream || die "拉取 upstream 失败"
    git checkout main --quiet
    git merge --ff-only upstream/main --quiet || die "main 无法快进到上游（可能有本地提交）"
    git checkout runtime --quiet
    if ! git merge main --no-edit --quiet; then
        git merge --abort || true
        die "runtime 合并 main 冲突，旧版本继续运行"
    fi
fi

head=$(git rev-parse HEAD)
lock_hash=$(sha256sum package-lock.json | cut -d' ' -f1)
deployed=$(cat "$stamp" 2>/dev/null || true)
expected="$head $lock_hash"

if ! $force && [[ $deployed == "$expected" ]]; then
    log "已是 $head，无需构建"
    exit 0
fi

# 2) 依赖（锁文件变化才重装；npm ci 会还原 Prisma 引擎，需重新补齐）
if [[ $(cut -d' ' -f2 <<<"$deployed") != "$lock_hash" ]] || [[ ! -d node_modules ]]; then
    log "安装依赖"
    npm ci --registry=https://registry.npmjs.org --no-audit --no-fund || die "npm ci 失败"
fi
scripts/local-prisma-engine.sh || die "Prisma 引擎准备失败"

log "生成 Prisma Client"
npx prisma generate --quiet 2>/dev/null || npx prisma generate || die "prisma generate 失败"

# 3) 数据库迁移（失败即中止，旧版本继续运行）
log "应用数据库迁移"
PRISMA_SCHEMA_ENGINE_BINARY="$repo/node_modules/@prisma/engines/schema-engine" \
    npx prisma migrate deploy || die "数据库迁移失败"

# 4) 构建到临时目录：版本号与提交号在此从 Git 推导，写入产物
version=$(git describe --tags --match 'v[0-9]*' --always 2>/dev/null || true)
log "构建 $head（版本 ${version:-package.json}）"
rm -rf "$staging"
if ! APP_VERSION="$version" APP_REVISION="$head" NEXT_DIST_DIR="$staging" npm run build >/tmp/wrong-notebook-build.log 2>&1; then
    tail -30 /tmp/wrong-notebook-build.log >&2
    rm -rf "$staging"
    die "构建失败，详见 /tmp/wrong-notebook-build.log"
fi

# Next 会因 distDir 变化改写 tsconfig.json 的 include 路径，还原以免工作区变脏影响下次自动部署
git checkout -- tsconfig.json 2>/dev/null || true

# 5) 布置 standalone：静态资源 + 指回仓库的软链（数据库、配置、证书都在仓库里）
cp -r public "$staging/standalone/public"
mkdir -p "$staging/standalone/.next"
cp -r "$staging/static" "$staging/standalone/.next/static"
for d in prisma config certs; do
    rm -rf "${staging:?}/standalone/$d"
    ln -sfn "$repo/$d" "$staging/standalone/$d"
done

# 6) 原子切换 + 重启（切换期间短暂停机，健康检查失败自动回滚）
log "切换产物并重启服务"
rm -rf "$backup"
mv "$dist" "$backup"
mv "$staging" "$dist"

restart() { systemctl --user restart "${services[@]}"; }
health() {
    for _ in $(seq 1 30); do
        if curl -fsS --max-time 3 http://127.0.0.1:3000/api/health >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

restart || { log "重启失败，回滚"; rm -rf "$dist"; mv "$backup" "$dist"; restart || true; die "重启失败"; }
if ! health; then
    log "健康检查未通过，回滚"
    systemctl --user stop "${services[@]}" || true
    rm -rf "$dist"
    mv "$backup" "$dist"
    restart || true
    die "健康检查失败，已回滚"
fi

rm -rf "$backup"
echo "$expected" > "$stamp"
log "部署完成：$(curl -fsS http://127.0.0.1:3000/api/health)"
