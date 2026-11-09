import type { NextConfig } from "next";

/**
 * 所有响应都带的安全头。
 * 不设置 HSTS（局域网常用 HTTP 访问），也不限制脚本来源（GeoGebra、KaTeX 等需要），
 * 只收紧不影响功能的部分：禁止被其他站点嵌入、禁止插件和改写 base/表单目标。
 */
export const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "SAMEORIGIN" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // 上传题目需要摄像头和屏幕截图，其余敏感能力一律关闭
  { key: "Permissions-Policy", value: "camera=(self), display-capture=(self), microphone=(), geolocation=(), payment=(), usb=()" },
  { key: "Content-Security-Policy", value: "frame-ancestors 'self'; base-uri 'self'; object-src 'none'; form-action 'self'" },
];

/**
 * AI 账户扩展：构建时用环境变量 AI_ACCOUNT_EXTENSION（相对项目根目录的文件路径）替换默认实现，见 doc/FORK_EXTENSION_POINTS.md。
 */
export function aiAccountExtensionPath(value = process.env.AI_ACCOUNT_EXTENSION): string {
  const path = value?.trim();
  if (!path) return './src/lib/ai/account-default.ts';
  return path.startsWith('.') || path.startsWith('/') ? path : `./${path}`;
}

const nextConfig: NextConfig = {
  output: 'standalone',
  turbopack: {
    resolveAlias: {
      '@wrong-notebook/ai-account': aiAccountExtensionPath(),
    },
  },
  serverExternalPackages: ['@prisma/client', 'bcryptjs'],
  // 禁止 next dev 自动向 AGENTS.md / CLAUDE.md 写入 agent-rules 段落
  agentRules: false,
  // 开发时允许通过环境变量放行局域网访问主机（逗号分隔），否则 HMR WebSocket 会被跨站防护拦截导致页面一直加载
  // 仅 next dev 生效，生产构建不受影响；例：NEXT_DEV_ALLOWED_ORIGINS=192.168.1.100
  allowedDevOrigins: process.env.NEXT_DEV_ALLOWED_ORIGINS
    ? process.env.NEXT_DEV_ALLOWED_ORIGINS.split(',').map((s) => s.trim()).filter(Boolean)
    : undefined,
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
