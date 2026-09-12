#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OCPP 1.6J 充电桩模拟器（纯标准库实现，零外部依赖，python3 直接跑）。

联调 claw 平台 OCPP 服务端（JSR-356 端点 /ocpp/{chargePointId}）：
  * 与服务端完成 WebSocket 握手
  * 周期发送 BootNotification / Heartbeat / StatusNotification / MeterValues
  * 模拟充电交易链路：状态转 "Charging" 时发 StartTransaction，
    从服务端 CALLRESULT 捕获 transactionId；状态回 "Available" 时发
    StopTransaction（带 transactionId + 当前电表读数），闭环结算 ChargeSession
  * 收到服务端下发的 CALL（SetChargingProfile / RemoteStartTransaction /
    RemoteStopTransaction / Reset / ChangeAvailability）自动回 CALLRESULT(Accepted)

帧格式（JSON over WebSocket，OCPP 1.6J）：
  CALL        = [2, "msgId", "Action", {payload}]
  CALLRESULT  = [3, "msgId", {payload}]
  CALLERROR   = [4, "msgId", "errorCode", "desc", {details}]

用法：
  python3 tools/ocpp_simulator.py --host localhost --port 8080 \
      --charge-point-id CP-001 --interval 10 --power 7000

  # 自定义鉴权令牌（与服务端 ocpp_charging_stations.auth_token 对齐才校验通过）
  python3 tools/ocpp_simulator.py --charge-point-id CP-002 --auth-token s3cr3t

退出：Ctrl+C
依赖：仅 python3 标准库（socket / hashlib / base64 / struct / json / threading）。
"""
import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import threading
import time

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def ws_handshake(sock, host, port, path):
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n"
        f"Sec-WebSocket-Protocol: ocpp1.6\r\n"
        f"\r\n"
    )
    sock.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError("WS 握手失败：连接被关闭")
        buf += chunk
    header = buf.split(b"\r\n\r\n", 1)[0]
    lines = header.split(b"\r\n")
    if "101" not in lines[0].decode():
        raise RuntimeError(f"WS 握手失败：{lines[0].decode()}")
    accept = None
    for line in lines[1:]:
        if line.lower().startswith(b"sec-websocket-accept:"):
            accept = line.split(b":", 1)[1].strip().decode()
    expected = base64.b64encode(
        hashlib.sha1((key + WS_GUID).encode()).digest()
    ).decode()
    if accept != expected:
        print("[warn] Sec-WebSocket-Accept 校验不一致（模拟器忽略，继续）")
    print("[ok] WS 握手成功")


def send_frame(sock, lock, text):
    """客户端→服务端帧必须加掩码（RFC 6455）。"""
    payload = text.encode("utf-8")
    mask = os.urandom(4)
    length = len(payload)
    header = bytes([0x81])
    if length < 126:
        header += bytes([0x80 | length])
    elif length < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", length)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", length)
    masked = bytes([payload[i] ^ mask[i % 4] for i in range(length)])
    with lock:
        sock.sendall(header + mask + masked)


def send_control(sock, lock, opcode, payload=b""):
    mask = os.urandom(4)
    length = len(payload)
    header = bytes([opcode | 0x80])
    header += bytes([length])  # 控制帧 payload < 126，忽略长帧
    masked = bytes([payload[i] ^ mask[i % 4] for i in range(length)])
    with lock:
        sock.sendall(header + mask + masked)


def recv_frame(sock):
    def read_n(n):
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                return None
            data += chunk
        return data

    h = read_n(2)
    if h is None:
        return None
    b0, b1 = h[0], h[1]
    opcode = b0 & 0x0F
    masked = (b1 & 0x80) != 0
    length = b1 & 0x7F
    if length == 126:
        length = struct.unpack(">H", read_n(2))[0]
    elif length == 127:
        length = struct.unpack(">Q", read_n(8))[0]
    mask_key = read_n(4) if masked else None
    payload = read_n(length)
    if payload is None:
        return None
    if masked:
        payload = bytes([payload[i] ^ mask_key[i % 4] for i in range(len(payload))])
    return opcode, payload


def handle_server_call(sock, lock, arr):
    """服务端下发 CALL：充电桩侧统一应答 CALLRESULT(Accepted)。"""
    msg_id = arr[1]
    action = arr[2] if len(arr) > 2 else ""
    print(f"  [CALL] 服务端下发动作: {action} (msgId={msg_id})")
    accepted = {
        "SetChargingProfile": {"status": "Accepted"},
        "RemoteStartTransaction": {"status": "Accepted", "transactionId": 1},
        "RemoteStopTransaction": {"status": "Accepted"},
        "Reset": {"status": "Accepted"},
        "ChangeAvailability": {"status": "Accepted"},
        "GetConfiguration": {"configurationKey": []},
        "ClearCache": {"status": "Accepted"},
    }
    result = accepted.get(action, {})
    resp = json.dumps([3, msg_id, result])
    send_frame(sock, lock, resp)
    print(f"  >> 应答 CALLRESULT {action}: {result}")


def handle_server_message(sock, lock, text, pending_start, tx_state):
    print(f"  << 服务端: {text}")
    try:
        arr = json.loads(text)
    except Exception:
        return
    if not isinstance(arr, list) or len(arr) < 2:
        return
    msg_type = arr[0]
    if msg_type == 2:  # CALL
        handle_server_call(sock, lock, arr)
    elif msg_type == 3:  # CALLRESULT
        msg_id = arr[1]
        payload = arr[2] if len(arr) > 2 else {}
        if msg_id in pending_start:
            # 这是我们对 StartTransaction 的应答：取出 transactionId 进入充电中
            tx_id = payload.get("transactionId") if isinstance(payload, dict) else None
            if tx_id is not None:
                tx_state["id"] = tx_id
                print(f"  [TX] StartTransaction 已确认 transactionId={tx_id}（进入充电中）")
            else:
                print(f"  [TX][warn] StartTransaction 应答缺少 transactionId: {payload}")
            pending_start.pop(msg_id, None)
        else:
            print(f"  [CALLRESULT] msgId={msg_id} payload={payload if isinstance(payload, dict) else ''}")
    elif msg_type == 4:  # CALLERROR
        print(f"  [CALLERROR] msgId={arr[1]} code={arr[2]} desc={arr[3] if len(arr) > 3 else ''}")


def reader_loop(sock, lock, stop, pending_start, tx_state):
    while not stop.is_set():
        try:
            res = recv_frame(sock)
        except Exception as e:
            if not stop.is_set():
                print(f"[recv error] {e}")
            break
        if res is None:
            if not stop.is_set():
                print("[conn] 服务端关闭连接")
            break
        opcode, payload = res
        if opcode == 0x8:  # close
            print("[conn] 收到 Close 帧")
            break
        elif opcode == 0x9:  # ping -> pong
            send_control(sock, lock, 0xA, payload)
        elif opcode == 0x1:  # text
            handle_server_message(
                sock, lock, payload.decode("utf-8", "ignore"), pending_start, tx_state
            )


def main():
    ap = argparse.ArgumentParser(description="OCPP 1.6J 充电桩模拟器（claw 平台联调用）")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--charge-point-id", default="CP-001", help="OCPP chargePointId（= WS 路径末段）")
    ap.add_argument("--auth-token", default=None, help="BootNotification 鉴权令牌（服务端有配才需传）")
    ap.add_argument("--interval", type=int, default=10, help="心跳/遥测周期（秒）")
    ap.add_argument("--power", type=int, default=7000, help="模拟充电功率 W（MeterValues 上报）")
    ap.add_argument("--energy-start", type=float, default=12000.0, help="起始累计电量 Wh")
    ap.add_argument("--connector-id", type=int, default=1)
    ap.add_argument("--id-tag", default="CP-USER", help="交易鉴权卡号 idTag（Start/StopTransaction 使用）")
    args = ap.parse_args()

    path = f"/ocpp/{args.charge_point_id}"
    print(f"[init] 连接 ws://{args.host}:{args.port}{path}")
    sock = socket.create_connection((args.host, args.port), timeout=10)
    ws_handshake(sock, args.host, args.port, path)

    stop = threading.Event()
    lock = threading.Lock()
    pending_start = {}            # msgId -> True：等待服务端确认 StartTransaction
    tx_state = {"id": None, "start_wh": None}  # 当前进行中的交易
    reader = threading.Thread(
        target=reader_loop,
        args=(sock, lock, stop, pending_start, tx_state),
        daemon=True,
    )
    reader.start()

    def send(text):
        send_frame(sock, lock, text)

    # 1) BootNotification（服务端据此自动建档站点 + 资产 + 注册 VPP）
    boot = [
        2, "boot-1", "BootNotification",
        {
            "chargePointVendor": "ClawSim",
            "chargePointModel": "Sim-1",
            "firmwareVersion": "1.0.0",
        },
    ]
    if args.auth_token:
        boot[3]["authToken"] = args.auth_token
    send(json.dumps(boot))
    time.sleep(0.5)

    # 2) 上报连接器空闲
    send(json.dumps([
        2, "st-init", "StatusNotification",
        {"connectorId": args.connector_id, "status": "Available", "errorCode": "NoError"},
    ]))

    seq = 0
    energy = args.energy_start
    prev_status = "Available"
    try:
        while not stop.is_set():
            time.sleep(args.interval)
            seq += 1
            # Heartbeat
            send(json.dumps([2, f"hb-{seq}", "Heartbeat", {}]))
            # 周期性状态：每 6 轮前 3 轮空闲，后 3 轮充电（seq%6<3 空闲）
            if seq % 6 < 3:
                status = "Available"
            else:
                status = "Charging"
            send(json.dumps([
                2, f"st-{seq}", "StatusNotification",
                {"connectorId": args.connector_id, "status": status, "errorCode": "NoError"},
            ]))

            # —— 交易链路状态机 ——
            if status == "Charging" and tx_state["id"] is None and prev_status != "Charging":
                # 状态由空闲转入充电：发起 StartTransaction
                start_msg_id = f"start-{seq}"
                pending_start[start_msg_id] = True
                tx_state["start_wh"] = int(energy)
                payload = {
                    "connectorId": args.connector_id,
                    "idTag": args.id_tag,
                    "meterStart": tx_state["start_wh"],
                }
                send(json.dumps([2, start_msg_id, "StartTransaction", payload]))
                print(f"  [TX] 发送 StartTransaction connector={args.connector_id} "
                      f"idTag={args.id_tag} meterStart={tx_state['start_wh']}")
            elif status == "Available" and tx_state["id"] is not None:
                # 状态由充电转回空闲：发起 StopTransaction 闭环结算
                payload = {
                    "transactionId": tx_state["id"],
                    "idTag": args.id_tag,
                    "meterStop": int(energy),
                }
                send(json.dumps([2, f"stop-{seq}", "StopTransaction", payload]))
                delivered = int(energy) - (tx_state["start_wh"] or 0)
                print(f"  [TX] 发送 StopTransaction transactionId={tx_state['id']} "
                      f"meterStop={int(energy)}（预计结算 {delivered} Wh）")
                tx_state["id"] = None
                tx_state["start_wh"] = None

            prev_status = status

            # 电量累加（仅充电中才涨，模拟真实电表）
            if status == "Charging":
                energy += args.power * (args.interval / 3600.0)
            meter = [
                2, f"mv-{seq}", "MeterValues",
                {
                    "connectorId": args.connector_id,
                    "meterValue": [
                        {
                            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                            "sampledValue": [
                                {"measurand": "Power.Active.Import", "unit": "W",
                                 "value": str(args.power if status == "Charging" else 0)},
                                {"measurand": "Energy.Active.Import.Register", "unit": "Wh",
                                 "value": str(int(energy))},
                            ],
                        }
                    ],
                },
            ]
            send(json.dumps(meter))
    except KeyboardInterrupt:
        print("\n[exit] 收到中断，关闭连接")
    finally:
        stop.set()
        try:
            send_control(sock, lock, 0x8)
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        print(f"[fatal] {e}", file=sys.stderr)
        sys.exit(1)
