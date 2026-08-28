package com.claw.server.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * 产品域出入参（common 层：不得 import 任何 domain 类，只用基础类型 / String / 枚举名 / BigDecimal / Instant）。
 * 对应 Increment 3 A 期：产品 admin CRUD + 模板字段 EAV + 产品详情（设备 + 共同属性）。
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** 产品视图（最小可用 admin CRUD）。 */
    public record ProductView(
            Long id,
            String name,
            Long manufacturerId,
            String assetType,
            String model,
            String brand,
            String category,
            String paramsJson,
            String attrJson,
            Integer reportIntervalSeconds,
            String status,
            Instant createdAt) {
    }

    /** 创建产品请求（资产类型以字符串透传，服务层做枚举校验）。 */
    public record CreateProductReq(
            String name,
            Long manufacturerId,
            String assetType,
            String model,
            String description,
            String brand,
            String category,
            String paramsJson,
            Integer reportIntervalSeconds,
            String attrJson) {
    }

    /** 更新产品请求（全字段可选，仅覆盖非空项）。 */
    public record UpdateProductReq(
            String name,
            Long manufacturerId,
            String assetType,
            String model,
            String description,
            String brand,
            String category,
            String paramsJson,
            Integer reportIntervalSeconds,
            String attrJson) {
    }

    /** 产品详情视图：产品本体 + 设备列表(按 product_id) + 共同属性(模板字段)。 */
    public record ProductDetailView(
            Long id,
            String name,
            String brand,
            String category,
            String assetType,
            String paramsJson,
            String attrJson,
            Integer reportIntervalSeconds,
            List<AssetSummaryView> devices,
            List<ProductTemplateFieldView> commonAttrs) {
    }

    /** 产品下设备摘要视图。 */
    public record AssetSummaryView(
            Long id,
            String assetNo,
            String assetType,
            Long productId,
            String status,
            Long ownerId) {
    }

    /** 模板字段视图。 */
    public record ProductTemplateFieldView(
            Long id,
            Long productId,
            String fieldKey,
            String label,
            String type,
            String unit,
            String optionsJson,
            boolean required,
            int sortNo) {
    }

    /** 创建模板字段请求。 */
    public record CreateProductTemplateFieldReq(
            Long productId,
            String fieldKey,
            String label,
            String type,
            String unit,
            String optionsJson,
            Boolean required,
            Integer sortNo) {
    }

    /** 更新模板字段请求（仅覆盖非空项）。 */
    public record UpdateProductTemplateFieldReq(
            String label,
            String type,
            String unit,
            String optionsJson,
            Boolean required,
            Integer sortNo) {
    }
}
