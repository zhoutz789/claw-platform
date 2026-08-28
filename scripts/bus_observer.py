#!/usr/bin/env python3
"""独立 MQTT 总线观察器：订阅 claw/iot/# 与 claw/telemetry/#，实时打印所有报文。
用于不依赖后端控制台日志，独立验证端到端 MQTT 闭环。"""
import sys
import time
from paho.mqtt import client as mqtt

TOPICS = [("claw/iot/#", 1), ("claw/telemetry/#", 1)]


def on_connect(c, u, f, rc, p=None):
    print(f"[obs] 已连接 rc={rc}", flush=True)
    for t, q in TOPICS:
        c.subscribe(t, q)
        print(f"[obs] 已订阅 {t}", flush=True)


def on_message(c, u, msg):
    try:
        body = msg.payload.decode("utf-8", "replace")
    except Exception:
        body = repr(msg.payload)
    print(f"[obs] <{msg.topic}> {body}", flush=True)


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    c.on_connect = on_connect
    c.on_message = on_message
    # 用平台后端服务账号鉴权（EMQX 要求认证）；该账号可订阅 claw/iot/#
    c.username_pw_set("claw-server", "claw-server-secret")
    c.connect_async("localhost", 1883, 60)
    c.loop_start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        c.loop_stop()
        c.disconnect()


if __name__ == "__main__":
    main()
