package com.claw.server.domain.station;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.StationRequests;
import com.claw.server.common.dto.StationViews;
import com.claw.server.common.security.AuthContext;
import com.claw.server.domain.commission.CommissionRule;
import com.claw.server.domain.commission.CommissionRuleRepository;
import com.claw.server.domain.manufacturer.Product;
import com.claw.server.domain.manufacturer.ProductRepository;
import com.claw.server.domain.manufacturer.ProductSku;
import com.claw.server.domain.manufacturer.ProductSkuRepository;
import com.claw.server.domain.settings.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务站结算层服务（模块四 · ③）。
 *
 * <p><b>解耦铁律（BC-1/BC-2）</b>：本服务的写事务只触碰
 * {@link StationSettlementRepository} 与 {@link StationSettlementItemRepository}；
 * 结算金额由"只读"引用跨层数据计算：消耗流水（inventory 层）、提成规则、SKU 货值、系统物流费率。
 * 不反向写任何其它层的表。
 *
 * <p>三金额计算顺序（D7/D8/D9）：
 * 物流费 = Σ(货值 × 物流费率)；服务站提成 = Σ(按厂家+商品命中的提成规则)；厂家净额 = Σ货值 − 物流费 − 提成。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StationSettlementService {

    private final StationSettlementRepository settlementRepository;
    private final StationSettlementItemRepository itemRepository;
    private final StationInventoryMovementRepository movementRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductRepository productRepository;
    private final CommissionRuleRepository commissionRuleRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final StationScopeService scopeService;

    /** 按 (stationId, 周期) 生成草稿：只读跨层数据，只写 settlements + items。 */
    @Transactional
    public StationViews.StationSettlementView generate(StationRequests.StationSettlementGenerate req, Long operatorId) {
        assertStationAllowed(req.stationId());
        Instant start = req.periodStart() != null ? req.periodStart() : Instant.EPOCH;
        Instant end = req.periodEnd() != null ? req.periodEnd() : Instant.now();

        // 消耗数据源：该站时间窗内 delta_qty<0 的出入库流水（结算只读引用）
        List<StationInventoryMovement> consumptions = movementRepository
                .findByStationIdAndDeltaQtyLessThanAndCreatedAtBetween(req.stationId(), 0, start, end);

        BigDecimal rate = logisticsRate();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal logisticsFee = BigDecimal.ZERO;
        BigDecimal stationCommission = BigDecimal.ZERO;

        for (StationInventoryMovement m : consumptions) {
            ProductSku sku = productSkuRepository.findBySkuCode(m.getSkuCode()).orElse(null);
            if (sku == null) {
                continue;
            }
            BigDecimal price = sku.getPrice() != null ? sku.getPrice() : BigDecimal.ZERO;
            BigDecimal value = price.multiply(BigDecimal.valueOf(Math.abs(m.getDeltaQty())))
                    .setScale(2, RoundingMode.HALF_UP);
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            totalValue = totalValue.add(value);
            logisticsFee = logisticsFee.add(value.multiply(rate).setScale(2, RoundingMode.HALF_UP));

            Long manufacturerId = null;
            if (sku.getProductId() != null) {
                Product product = productRepository.findById(sku.getProductId()).orElse(null);
                if (product != null) {
                    manufacturerId = product.getManufacturerId();
                }
            }
            stationCommission = stationCommission.add(commissionOf(manufacturerId, sku.getProductId(), value));
        }

        BigDecimal net = totalValue.subtract(logisticsFee).subtract(stationCommission)
                .setScale(2, RoundingMode.HALF_UP);

        StationSettlement settlement = StationSettlement.builder()
                .settlementNo("STL-" + req.stationId() + "-" + System.currentTimeMillis())
                .stationId(req.stationId())
                .periodStart(req.periodStart())
                .periodEnd(req.periodEnd())
                .status("DRAFT")
                .logisticsFee(logisticsFee.setScale(2, RoundingMode.HALF_UP))
                .stationCommission(stationCommission.setScale(2, RoundingMode.HALF_UP))
                .manufacturerNet(net)
                .currency("USD")
                .createdBy(operatorId)
                .build();
        settlement = settlementRepository.save(settlement);

        List<StationSettlementItem> items = new ArrayList<>();
        items.add(item("LOGISTICS", logisticsFee, "DEBIT", "物流费（厂家承担）", settlement.getId()));
        items.add(item("COMMISSION", stationCommission, "CREDIT", "服务站提成", settlement.getId()));
        items.add(item("RECOVERY", net, "CREDIT", "厂家净额回流", settlement.getId()));
        itemRepository.saveAll(items);

        log.info("生成服务站结算单 no={} stationId={} 货值={} 物流={} 提成={} 净额={}",
                settlement.getSettlementNo(), req.stationId(), totalValue, logisticsFee, stationCommission, net);
        return toView(settlement);
    }

    @Transactional
    public StationViews.StationSettlementView confirm(Long id, Long operatorId) {
        StationSettlement s = load(id);
        assertStationAllowed(s.getStationId());
        if (!"DRAFT".equals(s.getStatus())) {
            throw BizException.of(40900, "station.settlement.status.invalid");
        }
        s.setStatus("CONFIRMED");
        s.setConfirmedAt(Instant.now());
        return toView(settlementRepository.save(s));
    }

    @Transactional
    public StationViews.StationSettlementView pay(Long id, Long operatorId) {
        StationSettlement s = load(id);
        assertStationAllowed(s.getStationId());
        if (!"CONFIRMED".equals(s.getStatus())) {
            throw BizException.of(40900, "station.settlement.status.invalid");
        }
        s.setStatus("PAID");
        s.setPaidAt(Instant.now());
        return toView(settlementRepository.save(s));
    }

    @Transactional(readOnly = true)
    public List<StationViews.StationSettlementView> list(List<Long> allowedStationIds) {
        if (allowedStationIds != null && allowedStationIds.isEmpty()) {
            return List.of();
        }
        List<StationSettlement> all = allowedStationIds == null
                ? settlementRepository.findAll()
                : settlementRepository.findByStationIdInOrderByCreatedAtDesc(allowedStationIds);
        return all.stream().sorted(Comparator.comparing(StationSettlement::getCreatedAt).reversed())
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public StationViews.StationSettlementDetailView get(Long id) {
        StationSettlement s = load(id);
        List<StationViews.StationSettlementItemView> items = itemRepository
                .findBySettlementIdOrderByCreatedAtAsc(id).stream()
                .map(i -> new StationViews.StationSettlementItemView(
                        i.getId(), i.getSettlementId(), i.getItemType(), i.getRefId(),
                        i.getDescription(), i.getAmount(), i.getDirection(), i.getCreatedAt()))
                .toList();
        return new StationViews.StationSettlementDetailView(toView(s), items);
    }

    /* ----------------------------- 内部工具 ----------------------------- */

    private StationSettlement load(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("station.settlement.not.found"));
    }

    private StationSettlementItem item(String type, BigDecimal amount, String direction, String desc, Long settlementId) {
        return StationSettlementItem.builder()
                .settlementId(settlementId)
                .itemType(type)
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .direction(direction)
                .description(desc)
                .build();
    }

    /** 物流费率：取 system_config.STATION_LOGISTICS_FEE_RATE（默认 0，厂家承担）。 */
    private BigDecimal logisticsRate() {
        return systemConfigRepository.findByConfigKeyAndDeletedFalse("STATION_LOGISTICS_FEE_RATE")
                .map(c -> {
                    try {
                        return new BigDecimal(c.getConfigValue());
                    } catch (Exception e) {
                        return BigDecimal.ZERO;
                    }
                })
                .orElse(BigDecimal.ZERO);
    }

    /**
     * 提成匹配：在命中 (manufacturerId, productId) 的规则中，按优先级取高（D8）。
     * RATE → 货值 × rate；AMOUNT → 定额；无命中 → 0。
     */
    private BigDecimal commissionOf(Long manufacturerId, Long productId, BigDecimal value) {
        List<CommissionRule> candidates = new ArrayList<>();
        commissionRuleRepository.findByManufacturerIdAndProductIdAndEnabledTrue(manufacturerId, productId)
                .ifPresent(candidates::add);
        for (CommissionRule r : commissionRuleRepository.findByManufacturerIdAndEnabledTrue(manufacturerId)) {
            if (r.getProductId() == null) {
                candidates.add(r);
            }
        }
        for (CommissionRule r : commissionRuleRepository.findByProductIdAndEnabledTrue(productId)) {
            if (r.getManufacturerId() == null) {
                candidates.add(r);
            }
        }
        for (CommissionRule r : commissionRuleRepository.findByManufacturerIdIsNullAndEnabledTrue()) {
            if (r.getProductId() == null) {
                candidates.add(r);
            }
        }
        if (candidates.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // 优先级高者胜；同优先级按特异度（manufacturer+product > 单条件 > 平台默认）排序
        candidates.sort((a, b) -> {
            int c = Integer.compare(specificity(b), specificity(a));
            if (c != 0) {
                return c;
            }
            return Integer.compare(b.getPriority(), a.getPriority());
        });
        CommissionRule best = candidates.get(0);
        if ("RATE".equals(best.getCommissionType()) && best.getRate() != null) {
            return value.multiply(best.getRate()).setScale(2, RoundingMode.HALF_UP);
        } else if ("AMOUNT".equals(best.getCommissionType()) && best.getAmount() != null) {
            return best.getAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private int specificity(CommissionRule r) {
        int s = 0;
        if (r.getManufacturerId() != null) {
            s++;
        }
        if (r.getProductId() != null) {
            s++;
        }
        return s;
    }

    private StationViews.StationSettlementView toView(StationSettlement s) {
        return new StationViews.StationSettlementView(
                s.getId(), s.getSettlementNo(), s.getStationId(), s.getPeriodStart(), s.getPeriodEnd(),
                s.getStatus(), s.getLogisticsFee(), s.getStationCommission(), s.getManufacturerNet(),
                s.getCurrency(), s.getCreatedBy(), s.getCreatedAt(), s.getConfirmedAt(), s.getPaidAt());
    }

    /** 写操作越权校验（BC-5）。 */
    private void assertStationAllowed(Long stationId) {
        List<Long> allowed = scopeService.allowedStationIds(stationId);
        if (allowed != null && !allowed.contains(stationId)) {
            throw BizException.of(40301, "station.scope.forbidden");
        }
    }
}
