package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 服务站库存层服务（模块四 · ①）。
 *
 * <p><b>解耦铁律（BC-1）</b>：本服务的写事务只触碰 {@link StationStockRepository} 与
 * {@link StationInventoryMovementRepository}，<b>绝不</b>联动项目层 / 结算层。
 * 当前库存主表复用 {@link StationStock}（不新建库存主表，D1）。
 *
 * <p>可用量（stock_qty − Σallocated）由<b>项目层</b>读时计算，本层不读 alloc 表（避免跨层读耦合）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationInventoryService {

    private final StationStockRepository stockRepository;
    private final StationInventoryMovementRepository movementRepository;

    /** 当前库存列表（按 allowedStationIds 过滤；null=全平台，空=无数据）。 */
    @Transactional(readOnly = true)
    public List<StationViews.StationInventoryStockView> listStock(List<Long> allowedStationIds, String skuCode) {
        if (allowedStationIds != null && allowedStationIds.isEmpty()) {
            return List.of();
        }
        return stockRepository.findAll().stream()
                .filter(s -> allowedStationIds == null || allowedStationIds.contains(s.getStationId()))
                .filter(s -> skuCode == null || skuCode.equals(s.getSkuCode()))
                .sorted(Comparator.comparing(StationStock::getStationId)
                        .thenComparing(StationStock::getSkuCode))
                .map(this::toStockView)
                .toList();
    }

    /** 出入库流水（消耗数据源；按 allowedStationIds 过滤）。 */
    @Transactional(readOnly = true)
    public List<StationViews.StationInventoryMovementView> listMovements(List<Long> allowedStationIds, String skuCode) {
        if (allowedStationIds != null && allowedStationIds.isEmpty()) {
            return List.of();
        }
        List<StationInventoryMovement> movements = allowedStationIds == null
                ? movementRepository.findAll()
                : movementRepository.findByStationIdInOrderByCreatedAtDesc(allowedStationIds);
        List<StationViews.StationInventoryMovementView> out = new ArrayList<>();
        for (StationInventoryMovement m : movements) {
            if (skuCode != null && !skuCode.equals(m.getSkuCode())) {
                continue;
            }
            out.add(toMovementView(m));
        }
        out.sort(Comparator.comparing(StationViews.StationInventoryMovementView::createdAt).reversed());
        return out;
    }

    /** 服务站入站收货：+delta，维护 stock_qty 并写流水（同一写事务）。 */
    @Transactional
    public StationViews.StationInventoryStockView inbound(Long stationId, String skuCode, int qty) {
        StationStock stock = stockRepository.findByStationIdAndSkuCode(stationId, skuCode)
                .orElseGet(() -> StationStock.builder()
                        .stationId(stationId).skuCode(skuCode).stockQty(0).build());
        stock.setStockQty(stock.getStockQty() + qty);
        stock.setUpdatedAt(Instant.now());
        StationStock saved = stockRepository.save(stock);
        movementRepository.save(StationInventoryMovement.builder()
                .stationId(stationId).skuCode(skuCode).deltaQty(qty).reason("INBOUND")
                .operatorId(AuthContext.currentUserId()).createdAt(Instant.now()).build());
        log.info("服务站入站 stationId={} sku={} qty={}", stationId, skuCode, qty);
        return toStockView(saved);
    }

    /** 服务站盘点调整：±delta，维护 stock_qty 并写流水。 */
    @Transactional
    public StationViews.StationInventoryStockView adjust(Long stationId, String skuCode, int deltaQty, String reason) {
        if (deltaQty == 0) {
            throw BizException.invalidParam("station.inventory.adjust.zero");
        }
        StationStock stock = stockRepository.findByStationIdAndSkuCode(stationId, skuCode)
                .orElseGet(() -> StationStock.builder()
                        .stationId(stationId).skuCode(skuCode).stockQty(0).build());
        stock.setStockQty(stock.getStockQty() + deltaQty);
        stock.setUpdatedAt(Instant.now());
        StationStock saved = stockRepository.save(stock);
        String r = (reason == null || reason.isBlank()) ? "ADJUST" : reason.toUpperCase();
        movementRepository.save(StationInventoryMovement.builder()
                .stationId(stationId).skuCode(skuCode).deltaQty(deltaQty).reason(r)
                .operatorId(AuthContext.currentUserId()).createdAt(Instant.now()).build());
        log.info("服务站调整 stationId={} sku={} delta={} reason={}", stationId, skuCode, deltaQty, r);
        return toStockView(saved);
    }

    private StationViews.StationInventoryStockView toStockView(StationStock s) {
        return new StationViews.StationInventoryStockView(
                s.getId(), s.getStationId(), s.getSkuCode(), s.getStockQty(), s.getUpdatedAt());
    }

    private StationViews.StationInventoryMovementView toMovementView(StationInventoryMovement m) {
        return new StationViews.StationInventoryMovementView(
                m.getId(), m.getStationId(), m.getSkuCode(), m.getDeltaQty(), m.getReason(),
                m.getStationProjectId(), m.getRefType(), m.getRefId(), m.getOperatorId(), m.getCreatedAt());
    }
}
