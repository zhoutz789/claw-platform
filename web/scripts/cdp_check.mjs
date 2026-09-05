#!/usr/bin/env node
/**
 * 真实无头 Chrome 逐页实测脚本（CDP 驱动，零依赖，使用 Node 22 内置 WebSocket）。
 *
 * 用法：
 *   node cdp_check.mjs --mode single --path inventory-overview
 *   node cdp_check.mjs --mode sweep
 *
 * 每个用例使用**全新临时 user-data-dir**（全新 Chrome profile，localStorage 为空），
 * 导航到 http://localhost:5173/#/<path>，等待渲染后抓取：
 *   - 内容区首行文本
 *   - 是否 403（「抱歉，您当前账号没有访问该页面的权限」/ Result 403）
 *   - 是否崩溃（ErrorBoundary「页面渲染出现异常」）
 *   - console error 与未捕获异常（Runtime.exceptionThrown）
 *
 * 硬性约束：不重启 8080 / 5173；调试端口使用 9300+ 避免冲突。
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

const CHROME_BIN = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE_URL = 'http://localhost:5173';
const DEBUGGING_PORT = Number(process.env.CDP_PORT || 9333);

const PAGES = [
  'inventory-overview',
  'station-inventory',
  'station-projects',
  'station-settlements',
  'onboarding-apply',
  'onboarding-review',
  'onboarding-content',
  'onboarding-deposit-tiers',
  'onboarding-deposit-confirm',
  'org-manage',
  'sub-accounts',
  'airspace-zones',
  'flight-plans',
  'pilot-licenses',
  'drone-ops',
];

/** 启动一个带全新 profile 的无头 Chrome，返回进程句柄。 */
function launchChrome(profileDir) {
  // --no-sandbox / --disable-gpu-sandbox：本环境（受限 shell）下 Chrome 自带沙箱无法初始化，
  // 不禁用会直接 FATAL 退出；与被测页面逻辑无关。
  const args = [
    '--headless=new',
    `--user-data-dir=${profileDir}`,
    `--remote-debugging-port=${DEBUGGING_PORT}`,
    '--remote-allow-origins=*',
    '--no-sandbox',
    '--disable-gpu-sandbox',
    '--disable-gpu',
    '--disable-software-rasterizer',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-dev-shm-usage',
    '--no-proxy-server',
    '--window-size=1440,900',
    '--allow-insecure-localhost',
    'about:blank',
  ];
  return spawn(CHROME_BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
}

/** 等待 DevTools HTTP 端点就绪。 */
async function waitForDevTools(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${DEBUGGING_PORT}/json/version`, {
        signal: AbortSignal.timeout(1500),
      });
      if (res.ok) return await res.json();
    } catch {
      /* 未就绪，继续轮询 */
    }
    await sleep(300);
  }
  throw new Error('DevTools 端点在超时时间内未就绪');
}

/** 新建一个页面 target 并返回其 CDP WebSocket 地址。 */
async function createTarget(url) {
  const res = await fetch(
    `http://127.0.0.1:${DEBUGGING_PORT}/json/new?url=${encodeURIComponent(url)}`,
    { method: 'PUT', signal: AbortSignal.timeout(15000) },
  );
  if (!res.ok) throw new Error(`创建 target 失败: HTTP ${res.status}`);
  return await res.json();
}

/** 极简 CDP 客户端。 */
class CdpSession {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.nextId = 1;
    this.pending = new Map();
    this.events = [];
    this.ready = new Promise((resolve, reject) => {
      this.ws.addEventListener('open', () => resolve(), { once: true });
      this.ws.addEventListener('error', (e) => reject(new Error(`WS 连接失败: ${e.message || e}`)), { once: true });
    });
    this.ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }

  async send(method, params = {}) {
    const id = this.nextId++;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`CDP 命令超时: ${method}`));
        }
      }, 30000);
    });
  }

  close() {
    try { this.ws.close(); } catch { /* ignore */ }
  }
}

/** 在页面里执行的抓取脚本（返回字符串，由 CDP Runtime.evaluate 调用）。 */
const EXTRACT_FN = `(() => {
  const pick = (sel) => {
    const el = document.querySelector(sel);
    return el ? (el.innerText || '').trim() : '';
  };
  const content = pick('.ant-layout-content') || pick('main') || pick('#root') || document.body.innerText || '';
  const body = document.body ? (document.body.innerText || '') : '';
  const lines = content.split('\\n').map((s) => s.trim()).filter(Boolean);
  return JSON.stringify({
    firstLine: lines[0] || '',
    head: lines.slice(0, 6).join(' | '),
    has403: body.includes('403') || body.includes('没有访问该页面的权限') || body.includes('perm.forbiddenDesc'),
    hasErrorBoundary: body.includes('页面渲染出现异常') || body.includes('页面渲染出现异常'),
    perms: localStorage.getItem('claw_perms_v1') || '',
    token: localStorage.getItem('claw_token') || '',
    contentLen: content.length,
  });
})()`;

/**
 * 对单个页面做一次全新 profile 实测。
 * @param {string} path 路由路径
 * @returns {Promise<Object>} 实测结果
 */
export async function checkPage(path) {
  const profileDir = mkdtempSync(join(tmpdir(), 'claw-cdp-'));
  const chrome = launchChrome(profileDir);
  let session = null;
  try {
    await waitForDevTools();
    const target = await createTarget(`${BASE_URL}/#/${path}`);
    session = new CdpSession(target.webSocketDebuggerUrl);
    await session.ready;

    await session.send('Page.enable');
    await session.send('Runtime.enable');
    await session.send('Log.enable');
    await session.send('Network.enable');

    // 首帧就开始计时：导航后不再做任何 reload / 点击，用于验证 D1 自愈。
    await session.send('Page.navigate', { url: `${BASE_URL}/#/${path}` });

    let snapshot = null;
    const deadline = Date.now() + 18000;
    while (Date.now() < deadline) {
      await sleep(700);
      const res = await session.send('Runtime.evaluate', {
        expression: EXTRACT_FN,
        returnByValue: true,
        awaitPromise: false,
      });
      const value = res?.result?.value;
      if (!value) continue;
      snapshot = JSON.parse(value);
      // 稳定判据：内容已渲染（有首行）且权限已加载（perms 非空）
      if (snapshot.firstLine && snapshot.perms) break;
    }

    const errors = [];
    for (const ev of session.events) {
      if (ev.method === 'Runtime.exceptionThrown') {
        const d = ev.params?.exceptionDetails || {};
        errors.push(`EXCEPTION ${d.exception?.description || d.text || ''}`.slice(0, 400));
      } else if (ev.method === 'Log.entryAdded') {
        const e = ev.params?.entry || {};
        if (e.level === 'error') errors.push(`CONSOLE ${e.text || ''} ${e.url || ''}`.slice(0, 400));
      } else if (ev.method === 'Runtime.consoleAPICalled') {
        if (ev.params?.type === 'error') {
          const txt = (ev.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ');
          errors.push(`CONSOLE ${txt}`.slice(0, 400));
        }
      }
    }

    return {
      path,
      ok: true,
      firstLine: snapshot?.firstLine || '(空)',
      head: snapshot?.head || '',
      has403: Boolean(snapshot?.has403),
      hasErrorBoundary: Boolean(snapshot?.hasErrorBoundary),
      perms: snapshot?.perms || '',
      token: snapshot?.token || '',
      errors: [...new Set(errors)],
    };
  } catch (e) {
    return { path, ok: false, error: String(e.message || e), errors: [] };
  } finally {
    if (session) session.close();
    try { chrome.kill('SIGKILL'); } catch { /* ignore */ }
    await sleep(400);
    try { rmSync(profileDir, { recursive: true, force: true }); } catch { /* ignore */ }
  }
}

/** 主入口。 */
async function main() {
  const args = process.argv.slice(2);
  const modeIdx = args.indexOf('--mode');
  const mode = modeIdx >= 0 ? args[modeIdx + 1] : 'sweep';
  const pathIdx = args.indexOf('--path');
  const singlePath = pathIdx >= 0 ? args[pathIdx + 1] : '';

  const targets = mode === 'single' && singlePath ? [singlePath] : PAGES;
  const timesIdx = args.indexOf('--times');
  const times = timesIdx >= 0 ? Number(args[timesIdx + 1]) : 1;

  const results = [];
  for (const p of targets) {
    let fail403 = 0;
    let failCrash = 0;
    let last = null;
    for (let i = 0; i < times; i += 1) {
      // eslint-disable-next-line no-await-in-loop
      const r = await checkPage(p);
      last = r;
      if (r.has403) fail403 += 1;
      if (r.hasErrorBoundary || !r.ok) failCrash += 1;
      results.push(r);
      if (times > 1) {
        process.stdout.write(`  run#${i + 1} 403=${r.has403} crash=${r.hasErrorBoundary} first="${r.firstLine}"\n`);
      }
    }
    if (times === 1) {
      process.stdout.write(`[${p}] 403=${last.has403} CRASH=${last.hasErrorBoundary} FIRST="${last.firstLine}"\n`);
      if (last.errors.length) {
        for (const e of last.errors.slice(0, 3)) process.stdout.write(`    ERR ${e}\n`);
      }
      if (!last.ok) process.stdout.write(`    FAIL ${last.error}\n`);
    } else {
      process.stdout.write(`[${p}] x${times} -> 403次数=${fail403} 崩溃/失败次数=${failCrash}\n`);
    }
  }
  process.stdout.write(`\n=====JSON=====\n${JSON.stringify(results, null, 2)}\n`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
