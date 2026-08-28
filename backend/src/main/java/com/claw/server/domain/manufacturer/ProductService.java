package com.claw.server.domain.manufacturer;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ProductDtos.AssetSummaryView;
import com.claw.server.common.dto.ProductDtos.CreateProductReq;
import com.claw.server.common.dto.ProductDtos.ProductDetailView;
import com.claw.server.common.dto.ProductDtos.ProductTemplateFieldView;
import com.claw.server.common.dto.ProductDtos.ProductView;
import com.claw.server.common.dto.ProductDtos.UpdateProductReq;
import com.claw.server.common.enums.AssetType;
import com.claw.server.domain.asset.Asset;
import com.claw.server.domain.asset.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 产品服务：admin 级产品 CRUD（最小可用）+ 产品详情聚合（设备列表 + 共同属性）。
 *
 * <p>产品详情的设备列表走 {@code AssetRepository.findByProductId}（domain.asset 仓储）。
 * 现有 {@code ArchitectureBoundaryTest} 仅约束 ledger 仓储封闭与 common 纯净，
 * manufacturer → asset 仓储直查未被禁止（参见增量设计 §1.3 / 任务说明），故此处直查以保最小可用；
 * 若后续扩写 ArchUnit 规则要求 manufacturer 域封闭，可改为经 AssetService 暴露只读方法。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductTemplateFieldService fieldService;
    private final AssetRepository assetRepository;

    @Transactional
    public ProductView create(CreateProductReq req) {
        if (req.name() == null || req.assetType() == null) {
            throw BizException.invalidParam("error.product.required");
        }
        Product product = Product.builder()
                .manufacturerId(req.manufacturerId())
                .name(req.name())
                .assetType(parseAssetType(req.assetType()))
                .model(req.model())
                .description(req.description())
                .brand(req.brand())
                .category(req.category())
                .paramsJson(req.paramsJson())
                .reportIntervalSeconds(req.reportIntervalSeconds())
                .attrJson(req.attrJson())
                .build();
        return toView(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public List<ProductView> list() {
        return productRepository.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ProductDetailView detail(Long id) {
        Product product = load(id);
        List<AssetSummaryView> devices = assetRepository.findByProductId(id).stream()
                .map(a -> new AssetSummaryView(a.getId(), a.getAssetNo(),
                        a.getAssetType() == null ? null : a.getAssetType().name(),
                        a.getProductId(),
                        a.getStatus() == null ? null : a.getStatus().name(),
                        a.getOwnerId()))
                .toList();
        List<ProductTemplateFieldView> commonAttrs = fieldService.list(id);
        return new ProductDetailView(product.getId(), product.getName(), product.getBrand(),
                product.getCategory(),
                product.getAssetType() == null ? null : product.getAssetType().name(),
                product.getParamsJson(), product.getAttrJson(), product.getReportIntervalSeconds(),
                devices, commonAttrs);
    }

    @Transactional
    public ProductView update(Long id, UpdateProductReq req) {
        Product product = load(id);
        if (req.name() != null) {
            product.setName(req.name());
        }
        if (req.manufacturerId() != null) {
            product.setManufacturerId(req.manufacturerId());
        }
        if (req.assetType() != null) {
            product.setAssetType(parseAssetType(req.assetType()));
        }
        if (req.model() != null) {
            product.setModel(req.model());
        }
        if (req.description() != null) {
            product.setDescription(req.description());
        }
        if (req.brand() != null) {
            product.setBrand(req.brand());
        }
        if (req.category() != null) {
            product.setCategory(req.category());
        }
        if (req.paramsJson() != null) {
            product.setParamsJson(req.paramsJson());
        }
        if (req.reportIntervalSeconds() != null) {
            product.setReportIntervalSeconds(req.reportIntervalSeconds());
        }
        if (req.attrJson() != null) {
            product.setAttrJson(req.attrJson());
        }
        return toView(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw BizException.notFound("error.product.not.found");
        }
        productRepository.deleteById(id);
    }

    private AssetType parseAssetType(String s) {
        try {
            return AssetType.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("error.product.asset.type.invalid", s);
        }
    }

    private Product load(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("error.product.not.found"));
    }

    private ProductView toView(Product p) {
        return new ProductView(p.getId(), p.getName(), p.getManufacturerId(),
                p.getAssetType() == null ? null : p.getAssetType().name(),
                p.getModel(), p.getBrand(), p.getCategory(), p.getParamsJson(),
                p.getAttrJson(), p.getReportIntervalSeconds(), p.getStatus(), p.getCreatedAt());
    }
}
