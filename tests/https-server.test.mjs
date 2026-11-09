#!/usr/bin/env node
/**
 * https-server.js 的回归测试：
 * 启动本地上游 HTTP 服务与 HTTPS 代理，验证 GET/POST 转发与客户端中断处理。
 * 重点覆盖 Node 16+ 中 req 的 close 在请求体读完时即触发的行为——
 * 代理若据此销毁上游请求，所有 GET 都会 socket hang up（曾导致 502）。
 *
 * 运行：node --test tests/https-server.test.mjs
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const repoRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

let upstream;
let proxy;
let certDir;
let upstreamPort;
let proxyPort;

/** 向上游 HTTP 服务发请求 */
function httpRequest(port, options, body) {
    return new Promise((resolve, reject) => {
        const req = http.request({ hostname: '127.0.0.1', port, ...options }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => resolve({ status: res.statusCode, body: data }));
        });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

/** 经 HTTPS 代理发请求（自签证书，跳过校验） */
function proxyRequest(options, body) {
    return new Promise((resolve, reject) => {
        const req = https.request({ hostname: '127.0.0.1', port: proxyPort, rejectUnauthorized: false, ...options }, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => resolve({ status: res.statusCode, body: data }));
        });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

before(async () => {
    // 上游：回显方法与请求体，另提供慢响应用于中断测试
    upstream = http.createServer((req, res) => {
        if (req.url === '/slow') {
            setTimeout(() => { res.end('slow-done'); }, 3000);
            return;
        }
        let body = '';
        req.on('data', (chunk) => { body += chunk; });
        req.on('end', () => {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ method: req.method, url: req.url, body }));
        });
    });
    await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve));
    upstreamPort = upstream.address().port;

    // 自签测试证书（openssl 是仓库开发环境的既有依赖）
    certDir = mkdtempSync(path.join(tmpdir(), 'wn-https-test-'));
    execFileSync('openssl', [
        'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
        '-keyout', path.join(certDir, 'key.pem'),
        '-out', path.join(certDir, 'cert.pem'),
        '-subj', '/CN=localhost',
    ], { stdio: 'ignore' });

    // 随机空闲端口给代理
    const probe = http.createServer();
    await new Promise((resolve) => probe.listen(0, '127.0.0.1', resolve));
    proxyPort = probe.address().port;
    await new Promise((resolve) => probe.close(resolve));

    proxy = spawn(process.execPath, [path.join(repoRoot, 'https-server.js')], {
        env: {
            ...process.env,
            CERT_DIR: certDir,
            PORT: String(upstreamPort),
            HTTPS_PORT: String(proxyPort),
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    // 等待代理就绪
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('HTTPS 代理启动超时')), 10000);
        proxy.stdout.on('data', (chunk) => {
            if (String(chunk).includes('HTTPS 服务器已启动')) {
                clearTimeout(timer);
                resolve();
            }
        });
        proxy.on('exit', (code) => reject(new Error(`代理提前退出：${code}`)));
    });
});

after(() => {
    proxy?.kill();
    upstream?.close();
    if (certDir) rmSync(certDir, { recursive: true, force: true });
});

test('GET 经代理完整转发（req close 提前触发不掐断上游）', async () => {
    const res = await proxyRequest({ path: '/api/health', method: 'GET' });
    assert.equal(res.status, 200);
    const payload = JSON.parse(res.body);
    assert.equal(payload.method, 'GET');
    assert.equal(payload.url, '/api/health');
});

test('POST 请求体经代理完整转发', async () => {
    const body = JSON.stringify({ hello: '世界' });
    const res = await proxyRequest({
        path: '/api/data',
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, body);
    assert.equal(res.status, 200);
    const payload = JSON.parse(res.body);
    assert.equal(payload.method, 'POST');
    assert.equal(payload.body, body);
});

test('连续多次 GET 复用代理不出错', async () => {
    for (let i = 0; i < 5; i += 1) {
        const res = await proxyRequest({ path: `/seq/${i}`, method: 'GET' });
        assert.equal(res.status, 200);
        assert.equal(JSON.parse(res.body).url, `/seq/${i}`);
    }
});

test('客户端中断时代理不挂起且后续请求正常', async () => {
    // 发起慢请求后立刻中断
    await new Promise((resolve) => {
        const req = https.request({
            hostname: '127.0.0.1', port: proxyPort, path: '/slow', method: 'GET', rejectUnauthorized: false,
        });
        req.on('error', () => undefined);
        req.end();
        setTimeout(() => { req.destroy(); resolve(); }, 100);
    });
    const res = await proxyRequest({ path: '/after-abort', method: 'GET' });
    assert.equal(res.status, 200);
});