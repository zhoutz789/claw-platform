package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.IoTViews;
import com.claw.server.domain.iot.BmsCommand;
import com.claw.server.domain.iot.BmsControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 锂电池 BMS 下行控制（锂电池 BMS 对接方案 Phase C）。
 *
 * <p>指令经 {@link BmsControlService} → {@code DeviceCommandService}（HMAC 签名 + 审计落库 +
 * 已签名 {@code claw/iot/{deviceNo}/down} 下发）；设备回执经既有 cmd_ack 路由更新状态。
 * 仅对 {@code BATTERY_BMS} 设备生效（服务层已校验）。
 */
@RestController
@RequestMapping("/api/v1/bms")
@RequiredArgsConstructor
public class BmsControlController {

    private final BmsControlService bmsControlService;

    /** 下发一条 BMS 控制指令。body: {"action":"HEATER_ON","params":{"targetTemp":25}}。 */
    @PostMapping("/{deviceNo}/command")
    public ApiResult<IoTViews.CommandView> command(@PathVariable String deviceNo,
                                                   @RequestBody CommandReq req) {
        BmsCommand cmd;
        try {
            cmd = BmsCommand.fromAction(req.action());
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.bms.command.unknown", req.action());
        }
        return ApiResult.ok(bmsControlService.issueCommand(deviceNo, cmd, req.params()));
    }

    /** 列出全部可用 BMS 指令（供前端渲染控制面板）。 */
    @GetMapping("/commands")
    public ApiResult<List<String>> commands() {
        return ApiResult.ok(Arrays.stream(BmsCommand.values()).map(c -> c.action).toList());
    }

    public record CommandReq(String action, Map<String, Object> params) {
    }
}
