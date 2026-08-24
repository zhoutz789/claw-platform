#!/usr/bin/env bash
# Claw 后台 E2E 冒烟测试：闭环(厂家→商品→SKU→采购→支付→发货→登记二维码→资产出生→溯源→运营收益)
# + 数据大屏(真实指标+来源口径) + 权限目录/角色矩阵(菜单/增删改查/导出/按钮) + 用户角色授权。
# 关键：所有可变唯一键(厂家code/产品/序列号/资产编号)均带 $TS(纳秒)，保证重复运行不再撞 UNIQUE。
BASE=http://localhost:8080
PHONE=13800000001
TS=$(date +%s%N)          # 纳秒时间戳，保证同秒多次运行也唯一
PASS=0; FAIL=0
ok(){ PASS=$((PASS+1)); echo "  ✅ $1"; }
bad(){ FAIL=$((FAIL+1)); echo "  ❌ $1"; }

echo "=== 1. 认证（dev 短信回显 + 登录）==="
CODE=$(curl -s -X POST $BASE/api/v1/auth/sms-code -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\"}" | jq -r .data)
LOGIN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"code\":\"$CODE\"}")
TOKEN=$(echo "$LOGIN" | jq -r .data.token)
U_ID=$(echo "$LOGIN" | jq -r .data.userId)
echo "  claw userId = $U_ID, token.len = ${#TOKEN}"
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] && ok "拿到 JWT" || { bad "登录失败"; echo "$LOGIN"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"; CT="Content-Type: application/json"

echo "=== 2. 厂家 → 商品 → SKU → 采购 → 支付 → 发货 → 登记二维码（资产出生）==="
MFR=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/manufacturers -H "$AUTH" -H "$CT" -d "{\"code\":\"MFR-$TS\",\"name\":\"Claw 智造 $TS\",\"contact\":\"王工\",\"country\":\"CN\",\"status\":\"ACTIVE\"}")
MFR_ID=$(echo "$MFR" | jq -r .data.id); echo "  manufacturerId = $MFR_ID"
[ "$MFR_ID" != "null" ] && ok "创建厂家" || bad "创建厂家失败: $MFR"

PRD=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/products -H "$AUTH" -H "$CT" -d "{\"manufacturerId\":$MFR_ID,\"name\":\"社区电动三轮\",\"assetType\":\"VEHICLE\",\"model\":\"CTR-001\",\"description\":\"末端物流车\",\"status\":\"ON_SALE\"}")
PRD_ID=$(echo "$PRD" | jq -r .data.id); echo "  productId = $PRD_ID"
[ "$PRD_ID" != "null" ] && ok "创建商品" || bad "创建商品失败: $PRD"

SKU=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/skus -H "$AUTH" -H "$CT" -d "{\"productId\":$PRD_ID,\"skuCode\":\"SKU-$TS\",\"price\":1200.00,\"currency\":\"USD\",\"specsJson\":\"{}\",\"status\":\"ACTIVE\"}")
SKU_ID=$(echo "$SKU" | jq -r .data.id); echo "  skuId = $SKU_ID"
[ "$SKU_ID" != "null" ] && ok "创建 SKU 定价" || bad "创建SKU失败: $SKU"

# 采购单 buyer 必须是真实存在的 claw 用户（assets.owner_id 有外键）
PO=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/purchase-orders -H "$AUTH" -H "$CT" -d "{\"productId\":$PRD_ID,\"skuId\":$SKU_ID,\"buyerId\":$U_ID,\"qty\":2,\"unitPrice\":1200.00,\"currency\":\"USD\"}")
PO_ID=$(echo "$PO" | jq -r .data.id); PO_TOTAL=$(echo "$PO" | jq -r .data.totalAmount)
echo "  purchaseOrderId = $PO_ID, total=$PO_TOTAL"
[ "$PO_ID" != "null" ] && [ "$(echo "$PO_TOTAL >= 2400" | bc)" = "1" ] && ok "创建采购单(金额=单价×数量=2400)" || bad "采购单异常: $PO"

PAY=$(curl -s -X PUT $BASE/api/v1/admin/manufacturer/purchase-orders/$PO_ID/pay -H "$AUTH")
[ "$(echo "$PAY" | jq -r .data.status)" = "PAID" ] && ok "支付采购单(PAID)" || bad "支付失败: $PAY"

SHIP=$(curl -s -X PUT $BASE/api/v1/admin/manufacturer/purchase-orders/$PO_ID/ship -H "$AUTH")
[ "$(echo "$SHIP" | jq -r .data.status)" = "SHIPPED" ] && ok "发货(SHIPPED)" || bad "发货失败: $SHIP"

# 序列号带 $TS，规避 uk_assets_serial 跨运行唯一约束冲突
RQ=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/purchase-orders/$PO_ID/register-qr -H "$AUTH" -H "$CT" -d "{\"orderId\":null,\"items\":[{\"serialNumber\":\"SN-CTR-0001-$TS\",\"qrCode\":\"\",\"assetNo\":\"CTR-A0001-$TS\",\"assetType\":\"VEHICLE\"},{\"serialNumber\":\"SN-CTR-0002-$TS\",\"qrCode\":\"CLAW|ASSET|CTR-A0002-$TS|SN-CTR-0002-$TS\",\"assetNo\":\"CTR-A0002-$TS\",\"assetType\":\"VEHICLE\"}]}")
BORN=$(echo "$RQ" | jq -r .data.bornCount); BORN_IDS=$(echo "$RQ" | jq -r '.data.assetIds|join(",")')
echo "  bornCount = $BORN, assetIds = $BORN_IDS"
[ "$BORN" = "2" ] && ok "逐台登记二维码→出生 2 台资产" || bad "登记二维码失败: $RQ"

echo "=== 3. 资产溯源（出厂数据/生命周期/维修/使用/车辆运营/收益）==="
ASSET_ID=$(echo "$RQ" | jq -r '.data.assetIds[0]')
TRACE0=$(curl -s $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/trace -H "$AUTH")
SN=$(echo "$TRACE0" | jq -r .data.asset.serialNumber)
LC0=$(echo "$TRACE0" | jq -r '.data.lifecycle|length')
echo "  asset serial=$SN, 出厂生命周期事件数=$LC0"
[ "$SN" = "SN-CTR-0001-$TS" ] && [ "$LC0" -ge 1 ] && ok "溯源见到出厂数据+PRODUCED生命周期" || bad "溯源异常: $TRACE0"

curl -s -X POST $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/lifecycle -H "$AUTH" -H "$CT" -d '{"stage":"IN_USE","location":"金边站A","note":"投入运营"}' >/dev/null
curl -s -X POST $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/maintenance -H "$AUTH" -H "$CT" -d '{"servicedAt":"2026-08-20T08:00:00Z","mtype":"ROUTINE","vendor":"官方售后","cost":50.00,"note":"首保"}' >/dev/null
curl -s -X POST $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/usage -H "$AUTH" -H "$CT" -d '{"periodStart":"2026-08-01T00:00:00Z","periodEnd":"2026-08-31T00:00:00Z","mileageKm":820.50,"cycles":120,"energyKwh":340.20,"note":"8月里程"}' >/dev/null
OP=$(curl -s -X POST $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/vehicle-ops -H "$AUTH" -H "$CT" -d '{"opType":"PASSENGER","startedAt":"2026-08-15T06:00:00Z","endedAt":"2026-08-15T18:00:00Z","revenue":88.00,"detailJson":"{}","note":"客运日结"}')

TRACE1=$(curl -s $BASE/api/v1/admin/manufacturer/assets/$ASSET_ID/trace -H "$AUTH")
LC1=$(echo "$TRACE1" | jq -r '.data.lifecycle|length')
MN=$(echo "$TRACE1" | jq -r '.data.maintenance|length')
US=$(echo "$TRACE1" | jq -r '.data.usage|length')
VO=$(echo "$TRACE1" | jq -r '.data.vehicleOps|length')
REV=$(echo "$TRACE1" | jq -r .data.totalRevenue)
echo "  lifecycle=$LC1, maintenance=$MN, usage=$US, vehicleOps=$VO, totalRevenue=$REV"
[ "$LC1" -ge 2 ] && [ "$MN" = "1" ] && [ "$US" = "1" ] && [ "$VO" = "1" ] && [ "$(echo "$REV >= 88" | bc)" = "1" ] && ok "全生命周期数据闭环(收益累加=$REV)" || bad "生命周期数据缺失: $TRACE1"

echo "=== 4. 数据大屏（真实指标 + 来源口径）==="
DASH=$(curl -s $BASE/api/v1/admin/dashboard -H "$AUTH")
MFR_C=$(echo "$DASH" | jq -r .data.manufacturerCount)
PRD_C=$(echo "$DASH" | jq -r .data.productCount)
SKU_C=$(echo "$DASH" | jq -r .data.skuCount)
AST_C=$(echo "$DASH" | jq -r .data.assetCount)
PO_TOTAL2=$(echo "$DASH" | jq -r .data.purchaseTotal)
SRC_N=$(echo "$DASH" | jq -r '.data.sources|length')
echo "  manufacturerCount=$MFR_C, productCount=$PRD_C, skuCount=$SKU_C, assetCount=$AST_C, purchaseTotal=$PO_TOTAL2, sources=$SRC_N"
[ "$MFR_C" -ge 1 ] && [ "$PRD_C" -ge 1 ] && [ "$SKU_C" -ge 1 ] && [ "$AST_C" -ge 2 ] && [ "$(echo "$PO_TOTAL2 > 0" | bc)" = "1" ] && [ "$SRC_N" -ge 1 ] && ok "大屏指标来自真实表+含数据来源口径($SRC_N条)" || bad "大屏指标异常: $DASH"

echo "=== 5. 权限目录 + 角色矩阵（菜单/增删改查/导出/按钮）==="
CAT=$(curl -s $BASE/api/v1/admin/permissions/catalog -H "$AUTH")
CAT_N=$(echo "$CAT" | jq -r '.data|length')
echo "  permission catalog top nodes = $CAT_N"
[ "$CAT_N" -ge 1 ] && ok "权限目录(菜单+按钮树)可获取" || bad "权限目录异常: $CAT"

ROLES=$(curl -s $BASE/api/v1/admin/roles -H "$AUTH")
ROLE_ID=$(echo "$ROLES" | jq -r '.data[0].id'); ROLE_CODE=$(echo "$ROLES" | jq -r '.data[0].roleCode')
echo "  取角色 id=$ROLE_ID roleCode=$ROLE_CODE"
RM=$(curl -s $BASE/api/v1/admin/permissions/role/$ROLE_ID -H "$AUTH")
RM_N=$(echo "$RM" | jq -r '.data|length')
echo "  角色矩阵行数 = $RM_N"
[ "$RM_N" -ge 1 ] && ok "角色权限矩阵可获取($RM_N 行)" || bad "角色矩阵异常: $RM"

FIRST_PERM=$(echo "$RM" | jq -r '.data[0].permissionCode')
echo "  调整权限点 $FIRST_PERM 的 export=true 并回写"
NEW_ROWS=$(echo "$RM" | jq --arg p "$FIRST_PERM" '[.data[] | if .permissionCode==$p then (. + {canExport:true}) else . end]')
PUTRM=$(curl -s -X PUT $BASE/api/v1/admin/permissions/role/$ROLE_ID -H "$AUTH" -H "$CT" -d "{\"items\":$(echo "$NEW_ROWS" | jq -c .)}")
[ "$(echo "$PUTRM" | jq -r '.code')" = "0" ] && ok "角色权限矩阵可保存(切换按钮/导出位)" || bad "保存矩阵失败: $PUTRM"
RM2=$(curl -s $BASE/api/v1/admin/permissions/role/$ROLE_ID -H "$AUTH")
EXP2=$(echo "$RM2" | jq -r --arg p "$FIRST_PERM" '[.data[] | select(.permissionCode==$p)][0].canExport')
echo "  回读 $FIRST_PERM canExport = $EXP2"
[ "$EXP2" = "true" ] && ok "矩阵回写可持久化(canExport=true 落库)" || bad "矩阵回写未持久化: $RM2"

echo "=== 6. 平台用户授权（给用户增删改角色）==="
# 取一个真实用户（登录用户本身），整体替换其角色包，验证返回的活动角色列表
echo "  给 userId=$U_ID 授权角色 [$ROLE_CODE]，并回读"
UR=$(curl -s -X PUT $BASE/api/v1/admin/users/$U_ID/roles -H "$AUTH" -H "$CT" -d "{\"roleCodes\":[\"$ROLE_CODE\"]}")
UR_CODE=$(echo "$UR" | jq -r '.code'); UR_LIST=$(echo "$UR" | jq -r '.data|join(",")')
echo "  授权返回 code=$UR_CODE, 活动角色=$UR_LIST"
[ "$UR_CODE" = "0" ] && echo "$UR_LIST" | grep -q "$ROLE_CODE" && ok "平台用户角色授权可写回(返回活动角色含 $ROLE_CODE)" || bad "用户角色授权失败: $UR"
# 追加一个角色（若不存在则跳过断言），再移除验证 DELETE 也能用
UR_ADD=$(curl -s -X POST $BASE/api/v1/admin/users/$U_ID/roles -H "$AUTH" -H "$CT" -d "{\"roleCode\":\"$ROLE_CODE\"}")
[ "$(echo "$UR_ADD" | jq -r '.code')" = "0" ] && ok "追加角色接口可用(POST)" || bad "追加角色失败: $UR_ADD"
UR_DEL=$(curl -s -X DELETE $BASE/api/v1/admin/users/$U_ID/roles/$ROLE_CODE -H "$AUTH")
[ "$(echo "$UR_DEL" | jq -r '.code')" = "0" ] && ok "移除角色接口可用(DELETE)" || bad "移除角色失败: $UR_DEL"

echo "=============================================="
echo "  结果：PASS=$PASS  FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "  🎉 全绿：闭环 + 大屏 + 权限矩阵 + 用户授权 全部通过"; else echo "  ⚠️ 有 $FAIL 项失败，见上方 ❌"; fi
echo "=============================================="
exit $FAIL
