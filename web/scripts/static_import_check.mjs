#!/usr/bin/env node
/**
 * 静态漏导入检查器（Vite/ESBuild 不做类型检查，npm run build 抓不到运行时 ReferenceError）。
 *
 * 对每个入口页面文件（并**递归下钻**其相对路径 import 的本地 .js/.jsx），
 * 提取 JSX 中使用到的「大写开头」组件名，核对是否出现在：
 *   - import 绑定（named / default / namespace / renamed）
 *   - 本文件内的顶层声明（function / const / class / let / var）
 *   - 解构绑定（const { A, B } = ...）
 * 输出所有「未绑定即使用」的组件名及行号。
 *
 * 用法：node static_import_check.mjs
 */
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve, extname } from 'node:path';

const PAGES_DIR = process.env.PAGES_DIR_OVERRIDE
  || new URL('../src/pages/', import.meta.url).pathname;

const ENTRIES = [
  'InventoryOverview.jsx',
  'StationInventory.jsx',
  'StationProjects.jsx',
  'StationSettlement.jsx',
  'OnboardingApply.jsx',
  'OnboardingReview.jsx',
  'OnboardingContent.jsx',
  'OnboardingDepositTiers.jsx',
  'OnboardingDepositConfirm.jsx',
  'OrgManage.jsx',
  'SubAccounts.jsx',
  'AirspaceZones.jsx',
  'FlightPlans.jsx',
  'PilotLicenses.jsx',
  'DroneOps.jsx',
];

/** 把 import 语句中的绑定名全部抽出来。 */
function collectImports(src) {
  const names = new Set();
  const importRe = /import\s+([\s\S]*?)\s+from\s+['"][^'"]+['"]/g;
  let m;
  while ((m = importRe.exec(src)) !== null) {
    const clause = m[1].trim();
    if (!clause) continue;
    const braceMatch = clause.match(/\{([\s\S]*)\}/);
    if (braceMatch) {
      for (const part of braceMatch[1].split(',')) {
        const p = part.trim();
        if (!p) continue;
        // `X as Y` / `X as Y` 取本地名
        const asIdx = p.split(/\s+as\s+/);
        const local = (asIdx[1] || asIdx[0]).trim();
        if (local) names.add(local);
      }
    }
    // default import: 去掉大括号部分后的第一个标识符
    const withoutBraces = clause.replace(/\{[\s\S]*\}/, '').replace(/,/g, ' ').trim();
    if (withoutBraces && /^[A-Za-z_$][\w$]*$/.test(withoutBraces)) {
      names.add(withoutBraces);
    } else if (withoutBraces.startsWith('*')) {
      const asIdx = withoutBraces.split(/\s+as\s+/);
      const local = (asIdx[1] || '').trim();
      if (local) names.add(local);
    }
  }
  return names;
}

/** 收集本文件内的顶层声明与解构绑定。 */
function collectLocals(src) {
  const names = new Set();
  const patterns = [
    /(?:^|\n)\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)/g,
    /(?:^|\n)\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g,
    /(?:^|\n)\s*(?:export\s+)?class\s+([A-Za-z_$][\w$]*)/g,
  ];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(src)) !== null) names.add(m[1]);
  }
  // 解构：const { A, B: C } = ...
  const destrRe = /(?:const|let|var)\s*\{([\s\S]*?)\}\s*=/g;
  let d;
  while ((d = destrRe.exec(src)) !== null) {
    for (const part of d[1].split(',')) {
      const p = part.trim();
      if (!p) continue;
      const colon = p.split(':');
      const local = (colon[1] || colon[0]).trim();
      if (local && /^[A-Za-z_$][\w$]*$/.test(local)) names.add(local);
    }
  }
  // 函数形参（渲染子组件时常见）：function Foo({ Bar }) {}
  const paramRe = /function\s+[A-Za-z_$][\w$]*\s*\(\s*\{([\s\S]*?)\}\s*\)/g;
  let p;
  while ((p = paramRe.exec(src)) !== null) {
    for (const part of p[1].split(',')) {
      const seg = part.split(':')[0].trim();
      if (seg && /^[A-Za-z_$][\w$]*$/.test(seg)) names.add(seg);
    }
  }
  return names;
}

/** 收集 JSX 中使用到的大写开头组件名（含 Foo.Bar 的 Foo 部分）。 */
function collectJsxUsages(src) {
  const usages = [];
  const lines = src.split('\n');
  const jsxRe = /<\/?\s*([A-Z][\w$]*)(?:\.[\w$]+)*/g;
  lines.forEach((line, idx) => {
    // 跳过纯注释行与字符串内的尖括号（粗略：跳过以 // 或 * 开头的行）
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) return;
    let m;
    jsxRe.lastIndex = 0;
    while ((m = jsxRe.exec(line)) !== null) {
      usages.push({ name: m[1], line: idx + 1, raw: m[0] });
    }
  });
  return usages;
}

/** 抽取相对路径 import 的本地依赖，用于递归下钻。 */
function collectLocalDeps(src, fileDir) {
  const deps = [];
  const re = /import\s+(?:[\s\S]*?)\s+from\s+['"](\.[^'"]*)['"]/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    let p = resolve(fileDir, m[1]);
    const cands = [p, `${p}.jsx`, `${p}.js`, resolve(p, 'index.jsx'), resolve(p, 'index.js')];
    for (const c of cands) {
      if (existsSync(c) && (extname(c) === '.jsx' || extname(c) === '.js')) {
        deps.push(c);
        break;
      }
    }
  }
  return deps;
}

/** 对单个文件做检查。 */
function checkFile(file) {
  const src = readFileSync(file, 'utf8');
  const bound = new Set([...collectImports(src), ...collectLocals(src)]);
  const usages = collectJsxUsages(src);
  const missing = [];
  for (const u of usages) {
    if (!bound.has(u.name)) missing.push({ name: u.name, line: u.line, raw: u.raw });
  }
  return { file, missing, deps: collectLocalDeps(src, dirname(file)) };
}

/** 从入口出发递归检查（带 visited 防环）。 */
function checkEntry(entry) {
  const results = [];
  const visited = new Set();
  const queue = [resolve(PAGES_DIR, entry)];
  while (queue.length) {
    const f = queue.shift();
    if (visited.has(f)) continue;
    visited.add(f);
    let r;
    try {
      r = checkFile(f);
    } catch (e) {
      continue;
    }
    results.push(r);
    for (const d of r.deps) if (!visited.has(d)) queue.push(d);
  }
  return { entry, results };
}

/** 主入口。 */
function main() {
  let totalMissing = 0;
  for (const entry of ENTRIES) {
    const { results } = checkEntry(entry);
    const found = [];
    for (const r of results) {
      for (const m of r.missing) {
        found.push(`${r.file.replace(PAGES_DIR, 'src/pages/').replace(/^src\/pages\//, 'src/pages/')}:${m.line} -> <${m.name}>`);
      }
    }
    const rel = (p) => p.replace(/^.*\/src\//, 'src/');
    if (found.length) {
      totalMissing += found.length;
      console.log(`\n### ${entry} —— 发现 ${found.length} 处未绑定组件`);
      for (const f of found) console.log(`   ${rel(f)}`);
    } else {
      console.log(`\n### ${entry} —— OK（已下钻 ${results.length} 个文件，无未绑定组件）`);
    }
  }
  console.log(`\n===== 合计未绑定组件: ${totalMissing} =====`);
}

main();
