#!/usr/bin/env bash
# 让 Prisma 5 在只有 OpenSSL 3/4 的系统上可用：npm ci 会还原 openssl-1.1 引擎，这里补下 3.0 版本。
# 幂等：引擎能正常运行则立即返回。系统需要 libssl3（Ubuntu 26.10 为 libssl3t64）。
# 由 deploy-local.sh 在依赖变更后自动调用，也可单独执行。
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
engines="$repo/node_modules/@prisma/engines"
engine="$engines/schema-engine"

# 已就绪则跳过（能打印版本说明依赖库齐全）
if [[ -x $engine ]] && "$engine" --version >/dev/null 2>&1; then
    exit 0
fi

hash_dir=$(node -p "require('$repo/node_modules/@prisma/engines-version').enginesVersion")
base="https://binaries.prisma.sh/all_commits/$hash_dir/debian-openssl-3.0.x"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

echo "[prisma-engine] 下载 openssl-3.0.x 引擎 $hash_dir"
curl -fsSL -o "$tmp/schema-engine.gz" "$base/schema-engine.gz"
curl -fsSL -o "$tmp/libquery_engine.gz" "$base/libquery_engine.so.node.gz"
gunzip -f "$tmp/schema-engine.gz" "$tmp/libquery_engine.gz"
install -m 755 "$tmp/schema-engine" "$engine"
install -m 755 "$tmp/libquery_engine" "$engines/libquery_engine-debian-openssl-3.0.x.so.node"

"$engine" --version
echo "[prisma-engine] 完成"
