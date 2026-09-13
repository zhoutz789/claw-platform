package com.claw.server.domain.funds;

import com.claw.server.common.api.BizException;
import com.claw.server.common.enums.AccountType;
import com.claw.server.common.enums.LocationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 托管点位服务（L2）：映射注册 + 覆盖科目校验 + 读侧辅助。
 *
 * <p>回答「每笔钱在哪家机构的哪个账户」，并把破产隔离在 {@link LocationType} 上显式建模。
 * {@code covers_account_types} 为逗号分隔的 {@link AccountType} 名（审计用），
 * 注册时逐项校验其合法性；读侧解析为枚举列表供 L3 托管对账使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundsLocationService {

    /** 点位默认状态。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 默认币种（与 DDL DEFAULT 'USD' 一致）。 */
    public static final String DEFAULT_CURRENCY = "USD";

    private final FundsLocationRepository fundsLocationRepository;

    /**
     * 注册托管点位（幂等语义：编码已存在则拒绝，避免重复点位造成对账歧义）。
     *
     * @param location 待注册点位（{@code locationCode}/{@code institution}/{@code locationType} 必填）
     * @return 已落库点位
     * @throws BizException 必填缺失、编码重复、或覆盖科目非法
     */
    @Transactional
    public FundsLocation register(FundsLocation location) {
        if (location == null || isBlank(location.getLocationCode())) {
            throw BizException.invalidParam("error.funds.location.code.missing");
        }
        if (isBlank(location.getInstitution())) {
            throw BizException.invalidParam("error.funds.location.institution.missing");
        }
        if (location.getLocationType() == null) {
            throw BizException.invalidParam("error.funds.location.type.missing");
        }
        String code = location.getLocationCode().trim();
        if (fundsLocationRepository.existsByLocationCodeAndDeletedFalse(code)) {
            throw BizException.of(40960, "error.funds.location.code.exists", code);
        }
        // 覆盖科目逐项校验（非法 token 直接拒绝，避免对账口径漂移）
        parseCoveredAccountTypes(location.getCoversAccountTypes(), true);

        location.setLocationCode(code);
        if (isBlank(location.getCurrency())) {
            location.setCurrency(DEFAULT_CURRENCY);
        }
        if (isBlank(location.getStatus())) {
            location.setStatus(STATUS_ACTIVE);
        }
        if (location.getTenantId() == null) {
            location.setTenantId(1L);
        }
        if (location.getDeleted() == null) {
            location.setDeleted(false);
        }
        Instant now = Instant.now();
        if (location.getCreatedAt() == null) {
            location.setCreatedAt(now);
        }
        location.setUpdatedAt(now);
        return fundsLocationRepository.save(location);
    }

    /**
     * 按点位编码查询（只读）。
     *
     * @param locationCode 点位编码
     * @return 命中点位；编码为空或不存在时返回 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<FundsLocation> findByCode(String locationCode) {
        if (isBlank(locationCode)) {
            return Optional.empty();
        }
        return fundsLocationRepository.findByLocationCodeAndDeletedFalse(locationCode.trim());
    }

    /**
     * 按点位编码取点位（不存在则抛 404 语义异常）。
     *
     * @param locationCode 点位编码
     * @return 命中点位
     * @throws BizException 编码为空或不存在
     */
    @Transactional(readOnly = true)
    public FundsLocation getByCode(String locationCode) {
        if (isBlank(locationCode)) {
            throw BizException.invalidParam("error.funds.location.code.missing");
        }
        return findByCode(locationCode)
                .orElseThrow(() -> BizException.notFound("error.funds.location.not.found", locationCode));
    }

    /**
     * 按主键取点位（不存在或已逻辑删除则抛异常）。
     *
     * @param id 点位 id
     * @return 命中点位
     * @throws BizException 点位不存在
     */
    @Transactional(readOnly = true)
    public FundsLocation getById(Long id) {
        return fundsLocationRepository.findById(id)
                .filter(l -> !Boolean.TRUE.equals(l.getDeleted()))
                .orElseThrow(() -> BizException.notFound("error.funds.location.not.found", id));
    }

    /**
     * 解析点位覆盖的账本科目（{@code covers_account_types} CSV → {@link AccountType} 列表）。
     *
     * <p>读侧宽松：无法识别的 token 记警告并跳过（不阻断对账），供 L3 托管对账勾对使用。
     *
     * @param locationId 点位 id
     * @return 覆盖科目列表（可能为空）
     * @throws BizException 点位不存在
     */
    @Transactional(readOnly = true)
    public List<AccountType> coveredAccountTypes(Long locationId) {
        FundsLocation location = getById(locationId);
        return parseCoveredAccountTypes(location.getCoversAccountTypes(), false);
    }

    /**
     * 列出全部未删除点位（只读）。
     *
     * @return 点位列表
     */
    @Transactional(readOnly = true)
    public List<FundsLocation> list() {
        return fundsLocationRepository.findByDeletedFalse();
    }

    /**
     * 按类型 + 币种列出 ACTIVE 点位（只读）。
     *
     * @param locationType 点位类型
     * @param currency     币种
     * @return 点位列表
     */
    @Transactional(readOnly = true)
    public List<FundsLocation> listActiveByTypeAndCurrency(LocationType locationType, String currency) {
        String ccy = isBlank(currency) ? DEFAULT_CURRENCY : currency;
        return fundsLocationRepository.findByLocationTypeAndCurrencyAndStatusAndDeletedFalse(
                locationType, ccy, STATUS_ACTIVE);
    }

    /**
     * 标记点位最近一次对账完成（L3 托管对账锚点）。
     *
     * @param id 点位 id
     * @return 更新后的点位
     * @throws BizException 点位不存在
     */
    @Transactional
    public FundsLocation markReconciled(Long id) {
        FundsLocation location = getById(id);
        Instant now = Instant.now();
        location.setLastReconciledAt(now);
        location.setUpdatedAt(now);
        return fundsLocationRepository.save(location);
    }

    /**
     * 更新点位状态。
     *
     * @param id     点位 id
     * @param status 目标状态（如 ACTIVE / INACTIVE）
     * @return 更新后的点位
     * @throws BizException 点位不存在
     */
    @Transactional
    public FundsLocation updateStatus(Long id, String status) {
        FundsLocation location = getById(id);
        location.setStatus(isBlank(status) ? STATUS_ACTIVE : status);
        location.setUpdatedAt(Instant.now());
        return fundsLocationRepository.save(location);
    }

    /**
     * 解析 CSV 覆盖科目。
     *
     * @param csv    逗号分隔的 {@link AccountType} 名（可空）
     * @param strict {@code true} 遇到非法 token 直接抛异常（注册路径）；{@code false} 跳过并告警（读路径）
     * @return 科目列表
     * @throws BizException strict 模式下存在非法 token
     */
    private List<AccountType> parseCoveredAccountTypes(String csv, boolean strict) {
        List<AccountType> types = new ArrayList<>();
        if (isBlank(csv)) {
            return types;
        }
        for (String raw : csv.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                types.add(AccountType.valueOf(token));
            } catch (IllegalArgumentException e) {
                if (strict) {
                    throw BizException.invalidParam("error.funds.location.covered.type.invalid", token);
                }
                log.warn("[FundsLocation] 覆盖科目 token 无法识别，已跳过：{}", token);
            }
        }
        return types;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
