#!/usr/bin/env bash
# ============================================================================
# Claw 平台 T8 车辆/无人车资产 —— 真机 E2E 冒烟脚本
# ----------------------------------------------------------------------------
# 覆盖：认证 → 车型产品类(admin) → 车辆资产创建 → 车辆视图/能源/电池/轨迹 →
#       自主模块/驾驶模式/遥操作/语音/安全 → 地面围栏 → 自主任务派发与进度。
# 不依赖浏览器，纯 HTTP + jq，可在本地 H2 或 4 容器 PG 真机环境运行。
#
# 用法：
#   ./scripts/e2e-vehicle.sh                 # 默认 http://localhost:8080
#   BASE_URL=http://127.0.0.1:8080 ./scripts/e2e-vehicle.sh
#
# 退出码：0 = 全部通过；非 0 = 存在失败项。
# ============================================================================
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PHONE="13800000007"          # 平台管理员演示账号（local 种子注入，通配权限）
PASS=0; FAIL=0; WARN=0
JWT=""
GREEN=$'\033[32m'; RED=$'\033[31m'; YEL=$'\033[33m'; RST=$'\033[0m'

hr() { printf '%s\n' "────────────────────────────────────────────────────────────"; }
info() { printf '%s[INFO]%s %s\n' "$YEL" "$RST" "$1"; }
ok()   { printf '%s  ✓ %s%s\n' "$GREEN" "$1" "$RST"; PASS=$((PASS+1)); }
bad()  { printf '%s  ✗ %s%s\n' "$RED" "$1" "$RST"; FAIL=$((FAIL+1)); }
warn() { printf '%s  ⚠ %s%s\n' "$YEL" "$1" "$RST"; WARN=$((WARN+1)); }
# 断言 HTTP 状态码
expect() { # name got expect [note]
  local name="$1" got="$2" exp="$3" note="${4:-}"
  if [ "$got" = "$exp" ]; then ok "$name [$got] $note"; else bad "$name [got $got, want $exp] $note"; fi
}
# 断言状态码属于某集合（用于预期为 200 或预期为 4xx 的柔性校验）
expect_any() { # name got "200 409" note
  local name="$1" got="$2" set="$3" note="${4:-}" hit=0
  for c in $set; do [ "$got" = "$c" ] && hit=1; done
  if [ "$hit" = 1 ]; then ok "$name [$got] $note"; else bad "$name [got $got, want one of {$set}] $note"; fi
}

# ---- 底层 HTTP 助手 ----------------------------------------------------------
# 返回 "<http_code>\n<body>"
raw() {
  local m="$1" p="$2" j="${3:-}"
  if [ "$m" = "GET" ]; then
    curl -s -w $'\n%{http_code}' -X GET "$BASE$p" ${JWT:+-H "Authorization: Bearer $JWT"}
  else
    curl -s -w $'\n%{http_code}' -X "$m" "$BASE$p" ${JWT:+-H "Authorization: Bearer $JWT"} \
      -H 'Content-Type: application/json' -d "$j"
  fi
}
# 解析 raw() 输出
code_of() { echo "$1" | tail -1; }
body_of() { echo "$1" | sed '$d'; }
data_of() { body_of "$1" | jq -r '.data // empty'; }

hr
info "Claw T8 E2E 冒烟  →  $BASE"
info "演示账号: $PHONE (PLATFORM_ADMIN)"

# 0) 健康检查（排除未就绪）
H=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/actuator/health" || echo "000")
if [ "$H" != "200" ]; then
  bad "服务未就绪 (health=$H)。请先启动后端：mvn -o spring-boot:run -Plocal"
  echo; echo "结果：通过 $PASS / 失败 $FAIL / 柔性 $WARN"; exit 1
fi
ok "服务健康 (health=$H)"

# 1) 认证：sms-code（dev 回显）→ login
R=$(raw POST "/api/v1/auth/sms-code" "{\"phone\":\"$PHONE\"}")
expect "auth.sms-code" "$(code_of "$R")" "200"
CODE=$(data_of "$R")
[ -n "$CODE" ] && ok "auth.sms-code 回显码=$CODE" || bad "auth.sms-code 未回显验证码"

R=$(raw POST "/api/v1/auth/login" "{\"phone\":\"$PHONE\",\"code\":\"$CODE\"}")
expect "auth.login" "$(code_of "$R")" "200"
JWT=$(data_of "$R" | jq -r '.token // empty')
[ -n "$JWT" ] && ok "auth.login 获取 Bearer token" || bad "auth.login 未返回 token"
[ -z "$JWT" ] && { echo; echo "结果：通过 $PASS / 失败 $FAIL / 柔性 $WARN"; exit 1; }

# 1b) 负向：无 token 访问受保护资源应 401（显式不走 raw，避免注入 Bearer）
R=$(curl -s -w $'\n%{http_code}' -X GET "$BASE/api/v1/vehicles/1")
expect "auth.negative(no-token→401)" "$(code_of "$R")" "401"

# 2) 车型产品类（admin）
R=$(raw POST "/api/v1/admin/vehicle-product-classes/seed")
expect "vpc.seed" "$(code_of "$R")" "200"
SEED_N=$(data_of "$R")
ok "vpc.seed 播种数量=$SEED_N"

R=$(raw GET "/api/v1/admin/vehicle-product-classes")
expect "vpc.list" "$(code_of "$R")" "200"
LIST_N=$(data_of "$R" | jq 'length')
ok "vpc.list 返回 $LIST_N 条"

CODE_NEW="E2E_$(date +%s)"   # 防重入
BODY=$(jq -n --arg c "$CODE_NEW" '{code:$c,nameZh:"E2E车型",nameEn:"E2E Class",nameKm:"E2E",scenario:"DELIVERY",autonomyLevel:"FULL",capabilityTags:"[{\"k\":\"range\",\"v\":\"120\"}]",defaultDeviceTypes:"[{\"t\":\"VEHICLE_TCU\"}]",attrSchema:"[]",requiredCerts:"[]",geofencePreset:"{}"}')
R=$(raw POST "/api/v1/admin/vehicle-product-classes" "$BODY")
expect "vpc.create" "$(code_of "$R")" "200"

R=$(raw GET "/api/v1/admin/vehicle-product-classes/$CODE_NEW")
expect "vpc.getByCode" "$(code_of "$R")" "200"

ATTR=$(jq -n '{attrKey:"maxLoad",attrType:"NUMBER",unit:"kg",required:true,labelZh:"最大载重",labelEn:"Max Load",labelKm:"បន្ទុកអតិបរមា",sortOrder:1}')
R=$(raw POST "/api/v1/admin/vehicle-product-classes/$CODE_NEW/attrs" "$ATTR")
expect "vpc.addAttr" "$(code_of "$R")" "200"

R=$(raw GET "/api/v1/admin/vehicle-product-classes/$CODE_NEW/attrs")
expect "vpc.listAttrs" "$(code_of "$R")" "200"

# 2b) 负向：重复 code 应 409；不存在 code 应 404
R=$(raw POST "/api/v1/admin/vehicle-product-classes" "$BODY")
expect "vpc.dupCode→409" "$(code_of "$R")" "409"
R=$(raw GET "/api/v1/admin/vehicle-product-classes/NO_SUCH_CODE")
expect "vpc.notFound→404" "$(code_of "$R")" "404"

# 3) 创建车辆资产（作为后续端点目标）
VBODY='{"assetNo":"E2E-VEH-001","model":"E2E-Model-X"}'
R=$(raw POST "/api/v1/assets/vehicle" "$VBODY")
expect "asset.createVehicle" "$(code_of "$R")" "200"
VID=$(data_of "$R" | jq -r '.id // empty')
[ -n "$VID" ] && ok "asset.createVehicle vid=$VID" || bad "asset.createVehicle 未返回 id"

# 3b) 设备上线部署（绑定 VEHICLE_TCU，模拟真实车辆入网）。
#     控车指令（锁车/解锁/围栏下发等）依赖车辆存在 TCU 设备行，否则 safety.lock 会 404 TCU not found。
#     本步等价于真机 OCPP/MQTT 入网，是后续 autonomy.safety.lock 等端到端链路的前置条件。
BIND_BODY=$(jq -n --argjson id "$VID" '{assetId:$id,stationId:1,imei:"E2E-TCU-001"}')
R=$(raw POST "/api/v1/assets/$VID/bind" "$BIND_BODY")
expect "asset.bindDevice" "$(code_of "$R")" "200"

# 4) 车辆视图 / 能源 / 电池 / 轨迹
R=$(raw GET "/api/v1/vehicles/$VID")
expect "vehicle.get" "$(code_of "$R")" "200"
R=$(raw GET "/api/v1/vehicles/$VID/energy")
expect "vehicle.energy" "$(code_of "$R")" "200"
R=$(raw GET "/api/v1/vehicles/$VID/battery")
expect "vehicle.battery" "$(code_of "$R")" "200"
R=$(raw GET "/api/v1/vehicles/$VID/trajectory")
expect "vehicle.trajectory" "$(code_of "$R")" "200"
R=$(raw GET "/api/v1/vehicles/$VID/trajectory/latest")
expect_any "vehicle.trajectory.latest" "$(code_of "$R")" "200 204" "（无轨迹点时返回 200/null 或 204 均属正常）"

# 4b) 负向：不存在车辆应 404
R=$(raw GET "/api/v1/vehicles/999999999")
expect "vehicle.notFound→404" "$(code_of "$R")" "404"

# 5) 自主模块 / 驾驶模式 / 遥操作 / 语音 / 安全
R=$(raw POST "/api/v1/vehicles/$VID/autonomy/module" '{"algoVersion":"v1.0","driveMode":"ASSISTED"}')
expect "autonomy.createModule" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/drive-mode" '{"driveMode":"TELEOP"}')
expect "autonomy.setDriveMode" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/teleop/enter")
expect "autonomy.teleop.enter" "$(code_of "$R")" "200"
R=$(raw POST "/api/v1/vehicles/$VID/autonomy/teleop/exit")
expect "autonomy.teleop.exit" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/voice" '{"direction":"OUT","text":"请让一让","lang":"zh"}')
expect "autonomy.voice" "$(code_of "$R")" "200"

R=$(raw GET "/api/v1/vehicles/$VID/autonomy/safety")
expect "autonomy.safety.list" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/safety/lock")
expect "autonomy.safety.lock" "$(code_of "$R")" "200"

# 6) 地面围栏
R=$(raw GET "/api/v1/vehicles/geofences")
expect "geofence.list" "$(code_of "$R")" "200"
# 无围栏时校验应 409（no.work.zone）；有围栏且路径合法则 200。柔性断言。
R=$(raw POST "/api/v1/vehicles/geofences/validate" "{\"assetId\":$VID,\"pathJson\":\"[[104.9169,11.5621],[104.9175,11.5630]]\"}")
expect_any "geofence.validate" "$(code_of "$R")" "200 409" "（无围栏→409 / 路径合法→200 均属预期）"

# 7) 自主任务：创建 / 派发 / 进度 / 上报 / 列表
R=$(raw POST "/api/v1/vehicles/$VID/autonomy/tasks" '{"subtype":"DELIVERY","pathJson":"[[104.9169,11.5621],[104.9175,11.5630]]"}')
expect "task.create" "$(code_of "$R")" "200"
TID=$(data_of "$R" | jq -r '.id // empty')
[ -n "$TID" ] && ok "task.create tid=$TID" || bad "task.create 未返回 id"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/tasks/dispatch" "{\"taskId\":$TID,\"subtype\":\"DELIVERY\",\"pathJson\":\"[[104.9169,11.5621],[104.9175,11.5630]]\"}")
expect "task.dispatch" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/tasks/$TID/progress" '{"pct":50}')
expect "task.progress" "$(code_of "$R")" "200"

R=$(raw POST "/api/v1/vehicles/$VID/autonomy/tasks/report-progress" '{"pct":100}')
expect "task.report-progress" "$(code_of "$R")" "200"

R=$(raw GET "/api/v1/vehicles/$VID/autonomy/tasks")
expect "task.list" "$(code_of "$R")" "200"

# 7b) 负向：不存在任务回写进度应 404
R=$(raw POST "/api/v1/vehicles/$VID/autonomy/tasks/999999999/progress" '{"pct":10}')
expect "task.notFound→404" "$(code_of "$R")" "404"

hr
echo "E2E 结果：通过 $PASS / 失败 $FAIL / 柔性观察 $WARN"
[ "$FAIL" = "0" ] && echo "${GREEN}✅ T8 车辆/无人车资产链路 E2E 全部通过${RST}" || echo "${RED}❌ 存在 $FAIL 项失败，请检查上方明细${RST}"
exit $FAIL
