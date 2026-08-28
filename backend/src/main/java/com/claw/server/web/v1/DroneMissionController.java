package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.common.enums.DroneMissionType;
import com.claw.server.domain.payload.DroneMission;
import com.claw.server.domain.payload.DroneMissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 无人机作业计量：按载荷类型记录作业量（喷洒公顷/配送趟数/巡检里程/救援时长）。
 * 作为任务发布（TaskPublish）与分账的计量依据，复用既有任务与分账逻辑。
 */
@RestController
@RequestMapping("/api/v1/drone-missions")
@RequiredArgsConstructor
public class DroneMissionController {

    private final DroneMissionRepository missionRepository;

    @PostMapping
    public ApiResult<DroneMission> create(@RequestBody CreateMission req) {
        DroneMission m = DroneMission.builder()
                .assetId(req.assetId()).missionType(req.missionType()).payloadDesc(req.payloadDesc())
                .areaHa(req.areaHa()).trips(req.trips()).flightMinutes(req.flightMinutes())
                .pilotId(req.pilotId()).executedAt(req.executedAt() != null ? req.executedAt() : Instant.now())
                .build();
        return ApiResult.ok(missionRepository.save(m));
    }

    @GetMapping
    public ApiResult<List<DroneMission>> list(@RequestParam(required = false) Long assetId) {
        List<DroneMission> list = assetId != null
                ? missionRepository.findAll().stream().filter(m -> m.getAssetId().equals(assetId)).toList()
                : missionRepository.findAll();
        return ApiResult.ok(list);
    }

    public record CreateMission(Long assetId, DroneMissionType missionType, String payloadDesc,
                                BigDecimal areaHa, Integer trips, Integer flightMinutes,
                                Long pilotId, Instant executedAt) {
    }
}
