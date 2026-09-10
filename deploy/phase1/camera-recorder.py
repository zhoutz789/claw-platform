#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
边缘 recorder 模拟器 —— Phase 1 摄像头域 P0 自验证辅助脚本。

周期性（INTERVAL_SEC）向后端上报一段「视频段索引」，证明 V91 索引表写入路径
与 V92 种子相机（id=1）在端到端打通。真实边缘 recorder 还负责把视频段落盘到对象存储
（MinIO/OSS），此处仅 POST 合成 objectKey + size（对象存储落盘为 P1）。

依赖：仅 Python 标准库（urllib），无需 pip install，可在 python:3.11-slim 直接跑。

环境变量：
  CLAW_BACKEND   后端地址（默认 http://host.docker.internal:8080）
  CLAW_TOKEN     带 camera:manage 权限的 Bearer Token（必填，否则 401）
  CAMERA_ID      目标摄像头 ID（默认 1，对应 V92 种子相机）
  INTERVAL_SEC  上报周期秒（默认 30）
"""
import os
import sys
import time
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone, timedelta

BACKEND = os.environ.get("CLAW_BACKEND", "http://host.docker.internal:8080").rstrip("/")
TOKEN = os.environ.get("CLAW_TOKEN", "")
CAMERA_ID = int(os.environ.get("CAMERA_ID", "1"))
INTERVAL = int(os.environ.get("INTERVAL_SEC", "30"))


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S") + "Z"


def post_segment():
    now = datetime.now(timezone.utc)
    start = now - timedelta(seconds=INTERVAL)
    end = now
    payload = {
        "startTs": iso(start),
        "endTs": iso(end),
        "objectKey": "segments/%d/%s.mp4" % (CAMERA_ID, now.strftime("%Y%m%d%H%M%S")),
        "sizeBytes": 1024 * 1024 * 8,  # 合成：约 8MB / 段
        "tier": 1,                       # 热·边缘
        "eventTag": None,
    }
    url = "%s/api/v1/cameras/%d/segments" % (BACKEND, CAMERA_ID)
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": ("Bearer %s" % TOKEN) if TOKEN else "",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8")
            print("[ok] %s 段已上报: %s" % (iso(now), body[:120]), flush=True)
    except urllib.error.HTTPError as e:
        print("[err] HTTP %d: %s" % (e.code, e.read().decode("utf-8", "ignore")[:200]), flush=True)
    except Exception as e:  # noqa: BLE001
        print("[err] %s" % e, flush=True)


def main():
    if not TOKEN:
        print("[warn] 未设置 CLAW_TOKEN，后端将返回 401（camera:manage 校验）。"
              " 请先 POST /api/v1/auth/login 取 token 后 export CLAW_TOKEN=...", flush=True)
    print("[info] recorder 启动: backend=%s camera=%d interval=%ds" % (BACKEND, CAMERA_ID, INTERVAL), flush=True)
    while True:
        post_segment()
        time.sleep(INTERVAL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[info] recorder 已停止", flush=True)
        sys.exit(0)
