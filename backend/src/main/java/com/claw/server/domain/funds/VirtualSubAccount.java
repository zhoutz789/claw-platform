package com.claw.server.domain.funds;

import com.claw.server.common.enums.CustodyOwnerType;
import com.claw.server.common.enums.TaxpayerStatus;
import com.claw.server.common.enums.WhtCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 虚拟子户（对应 claw.virtual_subaccount，V128）。
 *
 * <p>用户/商家不占用真实银行账户，只在某个托管点位（{@link #fundsLocationId}）下开逻辑子户。
 * {@link #ownerUserId} 为映射到的账本用户（平台内部方为 NULL）——即本域与 ledger 域的
 * 映射锚点：{@code ownerUserId + currency → ledger account}。
 *
 * <p>幂等键：{@code (ownerType, ownerId, currency, fundsLocationId)}（部分唯一索引 uq_vsa_owner）。
 */
@Entity
@Table(name = "virtual_subaccount", schema = "claw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualSubAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 平台生成的虚拟子户号（唯一，机构侧映射）。 */
    @Column(name = "vsa_no", nullable = false, unique = true, length = 48)
    private String vsaNo;

    /** 持有方类型（USER/STATION/MANUFACTURER/INVESTOR/INSURER/LOGISTICS）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 24)
    private CustodyOwnerType ownerType;

    /** 持有方业务 id（用户/站/厂家/投资人的业务主键）。 */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 映射到的账本用户 id（平台内部方为 NULL）。 */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** 所属托管点位。 */
    @Column(name = "funds_location_id", nullable = false)
    private Long fundsLocationId;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    /** 机构侧子户号 —— 待通道确认是否存在。 */
    @Column(name = "external_sub_no", length = 64)
    private String externalSubNo;

    /** 纳税人状态（T11 WHT 代扣判定：REGISTERED/UNREGISTERED/INDIVIDUAL/NON_RESIDENT）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "taxpayer_status", length = 20)
    private TaxpayerStatus taxpayerStatus;

    /** WHT 类别（T11 WHT 代扣判定：SERVICE/RENTAL/DIVIDEND/NONE）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "wht_category", length = 20)
    private WhtCategory whtCategory;

    /** 状态：ACTIVE / FROZEN / CLOSED。 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 预留：KYC 分级 NONE/BASIC/ENHANCED（自持牌照规划，本期只留痕不启用）。 */
    @Column(name = "kyc_level", length = 16)
    private String kycLevel;

    /** 预留：日限额。 */
    @Column(name = "daily_limit")
    private BigDecimal dailyLimit;

    @Builder.Default
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Builder.Default
    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
