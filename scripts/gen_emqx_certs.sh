#!/usr/bin/env bash
# 生成 EMQX 双向 TLS 证书：Root CA / 服务端证书 / 平台客户端证书
# 用法：bash scripts/gen_emqx_certs.sh
set -eo pipefail

OUT_DIR="/Users/zhoutianzhi/WorkBuddy/Claw/claw-platform/deploy/ssl"
PW="claw123"
mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

echo "== 清理旧文件 =="
rm -f ca.* server.* client.* san.cnf

echo "== 1. Root CA =="
openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.pem \
  -subj "/CN=claw-iot-ca"

printf "subjectAltName=DNS:localhost,IP:127.0.0.1\n" > san.cnf

echo "== 2. EMQX 服务端证书（供 8883 监听） =="
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=localhost"
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -out server.pem -days 3650 -sha256 -extfile san.cnf

echo "== 3. 平台客户端证书（双向 TLS 客户端认证） =="
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=claw-platform"
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -out client.pem -days 3650 -sha256

echo "== 4. 客户端 PKCS12（平台 KeyManager 加载，口令 claw123） =="
openssl pkcs12 -export -in client.pem -inkey client.key -out client.p12 \
  -passout "pass:$PW" -name claw-client

echo ""
echo "完成。生成于 $OUT_DIR :"
echo "  ca.pem         -> 平台 trustStore（信任 EMQX 服务端）"
echo "  server.pem/key -> 拷入 EMQX 容器 /opt/emqx/etc/certs/ 并启用 8883 verify_peer"
echo "  client.p12     -> 平台双向 TLS 客户端证书（口令 $PW）"
