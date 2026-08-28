#!/usr/bin/env python3
"""测试 claw-server 是否能成功发布到 /up 与 /down 主题（观察者已订阅 claw/iot/#）。"""
import json
import sys
import time
from paho.mqtt import client as mqtt

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
c.username_pw_set("claw-server", "claw-server-secret")
c.connect("localhost", 1883, 60)
c.loop_start()
c.publish("claw/iot/CLAW-VT-TEST01/up", json.dumps({"marker": "FROM_SERVER_UP"}), qos=1)
c.publish("claw/iot/CLAW-VT-TEST01/down", json.dumps({"marker": "FROM_SERVER_DOWN"}), qos=1)
print("published up+down from claw-server", flush=True)
time.sleep(3)
c.loop_stop()
c.disconnect()
print("done", flush=True)
