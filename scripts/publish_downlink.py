#!/usr/bin/env python3
"""向 EMQX 下发下行报文（模拟平台侧 EmqxMqttCommandGateway.publish）。
使用平台后端服务账号 claw-server 鉴权，发布到 claw/iot/{deviceNo}/down。
后端入站适配器已订阅 claw/iot/#，会处理设备回执的 cmd_ack 并落库。"""
import json
import os
import time
from paho.mqtt import client as mqtt

DEVICE_NO = os.environ.get("DEVICE_NO", "CLAW-VT-TEST01")
CMD_ID = os.environ.get("CMD_ID", "")
ACTION = os.environ.get("ACTION", "relay")
STATE = int(os.environ.get("STATE", "0"))


def main():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    c.username_pw_set("claw-server", "claw-server-secret")
    c.connect("localhost", 1883, 60)
    c.loop_start()
    topic = f"claw/iot/{DEVICE_NO}/down"
    payload = json.dumps({
        "cmdId": CMD_ID,
        "action": ACTION,
        "params": {"state": STATE},
        "ts": int(time.time()),
        "nonce": "nonce-loop",
        "sign": "sig-loop",
    })
    info = c.publish(topic, payload, qos=1)
    info.wait_for_publish(timeout=5)
    print(f"[pub] 已下发 downlink topic={topic} cmdId={CMD_ID} action={ACTION} state={STATE}", flush=True)
    c.loop_stop()
    c.disconnect()


if __name__ == "__main__":
    main()
