/**
 * HTTPS wrapper for Next.js standalone server
 * 
 * 使用方法:
 * 1. 生成证书放到 /app/certs/ 目录
 * 2. 设置环境变量 HTTPS_ENABLED=true
 * 3. 容器会自动使用 HTTPS
 */
const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const CERT_DIR = process.env.CERT_DIR || '/app/certs';
const CERT_FILE = path.join(CERT_DIR, 'cert.pem');
const KEY_FILE = path.join(CERT_DIR, 'key.pem');

// 超时配置（毫秒）- 与 AI 分析超时保持一致
const PROXY_TIMEOUT = 300000; // 5 分钟，足够 AI 分析 + 重试

// 检查证书是否存在
if (!fs.existsSync(CERT_FILE) || !fs.existsSync(KEY_FILE)) {
    console.error('[HTTPS] 证书文件不存在:', CERT_DIR);
    console.error('[HTTPS] 请确保 cert.pem 和 key.pem 存在于该目录');
    process.exit(1);
}

// 读取证书
const options = {
    key: fs.readFileSync(KEY_FILE),
    cert: fs.readFileSync(CERT_FILE),
};

const port = parseInt(process.env.PORT || '3000', 10);

console.log(`[HTTPS] 启动 HTTPS 代理，端口 443 -> HTTP ${port}`);
console.log(`[HTTPS] 代理超时设置: ${PROXY_TIMEOUT / 1000} 秒`);

const httpsServer = https.createServer(options, (req, res) => {
    // 客户端安装包允许未登录下载，精确匹配路径，避免进入应用的登录保护。
    if ((req.method === 'GET' || req.method === 'HEAD') && req.url?.split('?')[0] === '/client.apk') {
        const apkPath = path.join(__dirname, 'public', 'client.apk');
        fs.stat(apkPath, (error, stat) => {
            if (error || !stat.isFile()) {
                res.writeHead(404);
                res.end();
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'application/vnd.android.package-archive',
                'Content-Disposition': 'attachment; filename="client.apk"',
                'Content-Length': stat.size,
                'Cache-Control': 'no-cache',
                'X-Content-Type-Options': 'nosniff',
            });
            if (req.method === 'HEAD') {
                res.end();
                return;
            }
            const file = fs.createReadStream(apkPath);
            file.on('error', () => res.destroy());
            res.on('close', () => file.destroy());
            file.pipe(res);
        });
        return;
    }
    const proxyReq = http.request({
        hostname: '127.0.0.1',
        port: port,
        path: req.url,
        method: req.method,
        headers: {
            ...req.headers,
            // 确保 Host 头正确
            host: req.headers.host || `127.0.0.1:${port}`,
        },
        // 设置代理请求超时
        timeout: PROXY_TIMEOUT,
    }, (proxyRes) => {
        // 复制响应头
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        // 流式传输响应体
        proxyRes.pipe(res);
    });

    // 设置客户端连接超时
    req.setTimeout(PROXY_TIMEOUT);
    res.setTimeout(PROXY_TIMEOUT);

    // 代理请求超时处理
    proxyReq.on('timeout', () => {
        console.error('[HTTPS] 代理请求超时');
        proxyReq.destroy();
        if (!res.headersSent) {
            res.writeHead(504);
            res.end('Gateway Timeout');
        }
    });

    // 代理错误处理
    proxyReq.on('error', (err) => {
        // 错误信息可能包含请求内容，去掉换行防止伪造日志行
        console.error('[HTTPS] 代理错误:', String(err.message).replace(/\n|\r/g, ''));
        if (!res.headersSent) {
            res.writeHead(502);
            res.end('Bad Gateway');
        }
    });

    // 客户端断开连接时，终止代理请求。
    // Node 16+ 中 IncomingMessage 的 close 在请求体读完时就会触发（并非连接断开），
    // 必须确认请求未完整接收才销毁，否则 GET 等无体请求的代理转发会被提前掐断（socket hang up）。
    req.on('close', () => {
        if (!req.complete) {
            proxyReq.destroy();
        }
    });
    // 响应结束后清理仍在上传的请求体（如客户端中途取消大文件上传）
    res.on('close', () => {
        if (!res.writableEnded) {
            proxyReq.destroy();
        }
    });

    // 将请求体转发到代理
    req.pipe(proxyReq);
});

// 服务器级别超时配置
httpsServer.timeout = PROXY_TIMEOUT;
httpsServer.keepAliveTimeout = 65000; // 65 秒 Keep-Alive
httpsServer.headersTimeout = 66000;   // 略大于 keepAliveTimeout

const httpsPort = parseInt(process.env.HTTPS_PORT || '443', 10);

httpsServer.listen(httpsPort, '0.0.0.0', () => {
    console.log(`[HTTPS] HTTPS 服务器已启动，监听端口 ${httpsPort}`);
});
