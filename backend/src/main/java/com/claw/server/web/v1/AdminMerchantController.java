package com.claw.server.web.v1;

import com.claw.server.common.api.ApiResult;
import com.claw.server.domain.merchant.Merchant;
import com.claw.server.domain.merchant.MerchantBooth;
import com.claw.server.domain.merchant.MerchantBoothRepository;
import com.claw.server.domain.merchant.MerchantRepository;
import com.claw.server.domain.merchant.MerchantZone;
import com.claw.server.domain.merchant.MerchantZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商家入驻骨架管理（增量 B · 商家域 Phase 2 骨架）。
 * 本期仅回填骨架（merchants / merchant_zones / merchant_booths 基础 CRUD），
 * 完整招商审批流留 Phase 2。
 */
@RestController
@RequestMapping("/api/v1/admin/merchants")
@RequiredArgsConstructor
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;
    private final MerchantZoneRepository zoneRepository;
    private final MerchantBoothRepository boothRepository;

    /* ---------------- 商家 ---------------- */
    @GetMapping
    public ApiResult<List<Merchant>> list() {
        return ApiResult.ok(merchantRepository.findByDeletedFalse());
    }

    @PostMapping
    public ApiResult<Merchant> create(@RequestBody UpsertMerchant req) {
        if (merchantRepository.findByCode(req.code()).isPresent()) {
            throw new com.claw.server.common.api.BizException(40901, "merchant.code.exists");
        }
        Merchant m = Merchant.builder()
                .code(req.code())
                .name(req.name())
                .contact(req.contact())
                .country(req.country())
                .status(req.status() == null ? "PENDING" : req.status())
                .build();
        return ApiResult.ok(merchantRepository.save(m));
    }

    @PutMapping("/{id}")
    public ApiResult<Merchant> update(@PathVariable Long id, @RequestBody UpsertMerchant req) {
        Merchant m = merchantRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "merchant.not.found"));
        if (req.name() != null) {
            m.setName(req.name());
        }
        if (req.contact() != null) {
            m.setContact(req.contact());
        }
        if (req.country() != null) {
            m.setCountry(req.country());
        }
        if (req.status() != null) {
            m.setStatus(req.status());
        }
        return ApiResult.ok(merchantRepository.save(m));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        Merchant m = merchantRepository.findById(id)
                .orElseThrow(() -> new com.claw.server.common.api.BizException(40401, "merchant.not.found"));
        m.setDeleted(true);
        merchantRepository.save(m);
        return ApiResult.ok();
    }

    /* ---------------- 区块 ---------------- */
    @GetMapping("/{merchantId}/zones")
    public ApiResult<List<MerchantZone>> listZones(@PathVariable Long merchantId) {
        return ApiResult.ok(zoneRepository.findByMerchantId(merchantId));
    }

    @PostMapping("/{merchantId}/zones")
    public ApiResult<MerchantZone> createZone(@PathVariable Long merchantId, @RequestBody UpsertZone req) {
        MerchantZone z = MerchantZone.builder()
                .merchantId(merchantId)
                .zoneCode(req.zoneCode())
                .name(req.name())
                .stationId(req.stationId())
                .status(req.status() == null ? "OPEN" : req.status())
                .build();
        return ApiResult.ok(zoneRepository.save(z));
    }

    /* ---------------- 铺位 ---------------- */
    @GetMapping("/zones/{zoneId}/booths")
    public ApiResult<List<MerchantBooth>> listBooths(@PathVariable Long zoneId) {
        return ApiResult.ok(boothRepository.findByZoneId(zoneId));
    }

    @PostMapping("/zones/{zoneId}/booths")
    public ApiResult<MerchantBooth> createBooth(@PathVariable Long zoneId, @RequestBody UpsertBooth req) {
        MerchantBooth b = MerchantBooth.builder()
                .zoneId(zoneId)
                .boothCode(req.boothCode())
                .name(req.name())
                .areaSqm(req.areaSqm())
                .monthlyRent(req.monthlyRent())
                .status(req.status() == null ? "AVAILABLE" : req.status())
                .build();
        return ApiResult.ok(boothRepository.save(b));
    }

    public record UpsertMerchant(String code, String name, String contact, String country, String status) {
    }

    public record UpsertZone(String zoneCode, String name, Long stationId, String status) {
    }

    public record UpsertBooth(String boothCode, String name, java.math.BigDecimal areaSqm,
                             java.math.BigDecimal monthlyRent, String status) {
    }
}
