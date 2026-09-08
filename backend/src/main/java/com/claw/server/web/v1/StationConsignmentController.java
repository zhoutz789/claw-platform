package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.InventoryViews;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.security.AuthContext;
import com.claw.server.common.security.RequirePermission;
import com.claw.server.domain.inventory.InventoryService;
import com.claw.server.domain.station.StationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务站寄售端点（V82 · 寄售入库发起方改造，前缀 /api/v1/station/consignment）。
 *
 * <p><b>设计意图</b>：寄售入库（建立 {@code consignment_custodies} 占有权）必须由服务站
 * 自己发起 —— 「谁操作数据是谁的，谁的数据是谁的」。厂家侧不再有「选站点把货分拨出去」
 * 的写接口，只保留只读库存视图；厂家要看明细报表走只读查询，不能操作数据。
 *
 * <p><b>两个关键 ID 都不来自入参</b>（避免越权与误操作）：
 * <ul>
 *   <li>站点 ID —— 由登录站长的作用域带出（{@link StationScopeService#currentStationId()}）；</li>
 *   <li>厂家 ID —— 由 {@code inventory.owner_manufacturer_id}（货权方，恒为厂家）带出。</li>
 * </ul>
 *
 * <p>写入链路完整复用 {@link InventoryService#stationConsignmentInbound}（内部走
 * {@code shipOne}）：OrgWritableGuard 禁用守卫 + 货值快照 + 授信额度硬阻断 +
 * 占有权/库存/设备状态/生命周期事件四处写入，语义与已下线的厂家分拨完全一致。
 */
@RestController
@RequestMapping("/api/v1/station/consignment")
@RequiredArgsConstructor
public class StationConsignmentController {

    private final InventoryService inventoryService;
    private final StationScopeService scopeService;

    /**
     * 服务站寄售入库（单台，前端逐台 / 扫码提交）。
     *
     * <p>入参只有 {@code deviceId}，站点与厂家均由服务端按登录身份推导。
     *
     * @param req 请求体（{@code {"deviceId": 123}}）
     * @return 入库结果（含货权厂家与占有权 ID，便于前端回显）
     * @throws BizException 10001 缺少 deviceId
     * @throws BizException 40301 当前账号不是一个确定的服务站主体（厂家/平台管理员/未绑定）
     * @throws BizException 40401 inventory.not.found（设备不在库存台账中）
     * @throws BizException 40943 inventory.owner_manufacturer.missing（台账缺货权方，无法入库）
     * @throws BizException 40944 inventory.custody.held.by.other.station（已被其它站占有）
     * @throws BizException 40945 inventory.custody.duplicate.inbound（本站重复入库）
     * @throws BizException 40941 credit.limit.exceeded（超出服务站授信额度，硬阻断）
     * @throws BizException 40340 org.disabled.readonly（服务站已被平台禁用，禁止新货入站）
     */
    @PostMapping("/inbound")
    @RequirePermission("station:consignment:inbound")
    public ApiResult<InventoryViews.ConsignmentInboundResult> inbound(
            @RequestBody StationRequests.StationConsignmentInbound req) {
        if (req == null || req.deviceId() == null) {
            throw BizException.invalidParam("station.consignment.inbound.device_required");
        }
        // 站点 ID 永不来自入参：由登录站长的作用域带出（BC-5 同款失败关闭）
        Long stationId = scopeService.currentStationId();
        Long operatorId = AuthContext.currentUserId();
        return ApiResult.ok(inventoryService.stationConsignmentInbound(req.deviceId(), stationId, operatorId));
    }
}
