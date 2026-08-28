#!/usr/bin/env python3
"""爪平台车辆终端（VTU）模拟器：连接 EMQX，发上行报文并回执下行指令。

用于联调端到端链路（无需真实硬件）：
  - 连接 MQTT（username=device_no, password=secret）验证设备级鉴权
  - 定时发布 location 上行
  - 接收 down 指令并回 cmd_ack（模拟执行 relay）

用法：
  python3 sim_device.py --device-no CLAW-VT-1 --secret <secret> --host localhost --port 1883
  TLS 双向： --port 8883 --tls --ca deploy/ssl/ca.pem --cert deploy/ssl/client.p12 --cert-pass claw123
"""
import argparse
import json
import ssl
import sys
import time

try:
    from paho.mqtt import client as mqtt
except ImportError:
    print("缺少 paho-mqtt，请先: pip install paho-mqtt", file=sys.stderr)
    sys.exit(2)


def on_connect(client, userdata, flags, rc, properties=None):
    print(f"[sim] 已连接 rc={rc}（0=成功；非0多为鉴权失败/网络问题）")
    if rc == 0:
        client.subscribe(f"claw/iot/{userdata['device_no']}/down", qos=1)
        print(f"[sim] 已订阅 claw/iot/{userdata['device_no']}/down")


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode())
    except Exception:
        print("[sim] 下行非 JSON，丢弃")
        return
    print(f"[sim] 收到下行: {msg.payload.decode()}")
    cmd_id = payload.get("cmdId", "")
    action = payload.get("action", "")
    # 模拟执行并返回真实电平（真实固件应做签名验签 + 安全条件判断）
    if action == "relay":
        state = payload.get("params", {}).get("state", 1)
        print(f"[sim] 执行 relay -> {'断开(不可启动)' if state == 0 else '吸合(可启动)'}")
    ack = {
        "msgType": "cmd_ack",
        "deviceId": userdata["device_no"],
        "cmdId": cmd_id,
        "result": "OK",
        "detail": f"sim executed {action}",
    }
    client.publish(f"claw/iot/{userdata['device_no']}/up", json.dumps(ack), qos=1)
    print(f"[sim] 已回执 cmdId={cmd_id}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device-no", required=True)
    ap.add_argument("--secret", required=True)
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--tls", action="store_true", help="启用 TLS")
    ap.add_argument("--ca", help="CA 证书(PEM)，双向 TLS 验证服务端")
    ap.add_argument("--cert", help="客户端证书(PKCS12)，双向 TLS 客户端认证")
    ap.add_argument("--cert-pass", default="claw123")
    ap.add_argument("--interval", type=float, default=5.0)
    args = ap.parse_args()

    cfg = {"device_no": args.device_no}
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata=cfg)
    client.username_pw_set(args.device_no, args.secret)
    client.on_connect = on_connect
    client.on_message = on_message

    if args.tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        if args.ca:
            ctx.load_verify_locations(args.ca)
        else:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        if args.cert:
            ctx.load_cert_chain(args.cert, password=args.cert_pass)
        client.tls_set_context(ctx)

    client.connect_async(args.host, args.port, 60)
    client.loop_start()

    seq = 0
    try:
        while True:
            seq += 1
            loc = {
                "msgType": "location",
                "deviceId": args.device_no,
                "lat": 34.26140 + seq * 0.0001,
                "lng": 117.18420 + seq * 0.0001,
                "speed": 0.0,
                "course": 92.5,
                "alt": 42.0,
                "acc": 0,
                "battery": 12.6,
                "rssi": -78,
            }
            client.publish(f"claw/iot/{args.device_no}/up", json.dumps(loc), qos=1)
            print(f"[sim] 上行 location #{seq}")
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("[sim] 停止")
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
