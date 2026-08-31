#!/usr/bin/env python3
# ==============================================================================
# i18n 文案覆盖率审计（messageCode <-> messages_{en,km,zh}.properties）
#
# 用途：
#   1. 找出 Java 里抛出、但语言包里没有对应 key 的 messageCode —— 这类 key
#      经 MessageSource 回落后会原样透给客户端，用户看到的是
#      "commission.rule.not.found" 而不是人话。
#   2. 找出只在部分语言包里存在的 key（按 Accept-Language 静默降级）。
#   3. 找出语言包里有、Java 里从未使用的死 key。
#
# 为什么需要它：
#   这类问题不会让编译失败、不会让单测变红、也不会产生任何日志 —— 只有真机
#   点开页面、或在抓包里看响应体才发现。本项目支持英/柬/中三语，任意一个
#   语言包漏 key 都只影响该语言的客户端，人工回归极易漏掉。
#
# 用法：
#   python3 scripts/audit-i18n.py                # 打印报告
#   python3 scripts/audit-i18n.py --strict       # 有 [A]/[B] 类问题时退出码 1（CI 门禁）
#   python3 scripts/audit-i18n.py --backend DIR  # 指定 backend 目录
#
# 依赖：Python 3.10+，无第三方库
# ==============================================================================
"""Audit i18n message-code coverage between Java sources and the message bundles."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 匹配两类抛出点：
#   1) BizException.of(40401, "some.key") / new BizException(40401, "some.key")
#   2) BizException.invalidParam("some.key") / notFound / forbidden / unauthorized
MSG_RE = re.compile(
    r"(?:BizException\.of|new\s+(?:com\.claw\.server\.common\.api\.)?BizException)"
    r"\s*\(\s*\d{4,5}\s*,\s*\"([A-Za-z0-9_.]+)\""
    r"|BizException\.(?:invalidParam|notFound|forbidden|unauthorized)"
    r"\s*\(\s*\"([A-Za-z0-9_.]+)\""
)


def collect_used_codes(src_dir: Path) -> dict[str, list[str]]:
    """Return messageCode -> list of 'File.java:line' references found in Java sources."""
    used: dict[str, list[str]] = {}
    for path in sorted(src_dir.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for match in MSG_RE.finditer(text):
            key = match.group(1) or match.group(2)
            line = text[: match.start()].count("\n") + 1
            used.setdefault(key, []).append(f"{path.name}:{line}")
    return used


def load_bundle(path: Path) -> set[str]:
    """Parse a .properties file, ignoring comments and blank lines."""
    keys: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        if "=" in line:
            keys.add(line.split("=", 1)[0].strip())
    return keys


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit i18n message-code coverage.")
    parser.add_argument(
        "--backend",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "backend",
        help="backend 模块根目录（默认：脚本所在目录的 ../backend）",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="存在 [A]/[B] 类问题时以退出码 1 结束（用于 CI 门禁）",
    )
    args = parser.parse_args()

    src_dir = args.backend / "src/main/java"
    i18n_dir = args.backend / "src/main/resources/i18n"
    if not src_dir.is_dir() or not i18n_dir.is_dir():
        print(f"找不到源码或语言包目录：{src_dir} / {i18n_dir}", file=sys.stderr)
        return 2

    used = collect_used_codes(src_dir)
    bundles = {
        p.stem.replace("messages_", ""): load_bundle(p)
        for p in sorted(i18n_dir.glob("messages_*.properties"))
    }
    if not bundles:
        print(f"未找到任何 messages_*.properties：{i18n_dir}", file=sys.stderr)
        return 2

    print(f"Java 中使用的 messageCode：{len(used)} 个")
    for name, keys in bundles.items():
        print(f"  messages_{name}.properties：{len(keys)} 个 key")

    all_keys = set().union(*bundles.values())

    missing_all = sorted(k for k in used if k not in all_keys)
    print(f"\n=== [A] 三个语言包都不存在（客户端一定看到裸 key）：{len(missing_all)} 个 ===")
    for key in missing_all:
        print(f"  {key}   <- {used[key][0]}")

    print("\n=== [B] 仅部分语言包缺失（按 Accept-Language 静默降级）===")
    partial = 0
    for key in sorted(used):
        if key in all_keys:
            lacking = sorted(n for n, ks in bundles.items() if key not in ks)
            if lacking:
                partial += 1
                print(f"  {key}：缺 {lacking}")
    if partial == 0:
        print("  （无）")

    dead = sorted(k for k in all_keys if k not in used)
    print(f"\n=== [C] 语言包里有但 Java 从未使用（死 key，仅提示）：{len(dead)} 个 ===")
    if len(dead) <= 40:
        for key in dead:
            print(f"  {key}")

    problems = len(missing_all) + partial
    print(f"\n结论：{'FAIL' if problems else 'PASS'} —— 待补 {problems} 项（A 类 {len(missing_all)}，B 类 {partial}）")
    if args.strict and problems:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
