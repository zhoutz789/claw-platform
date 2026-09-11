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
import com.claw.server.domain.category.Category;
import com.claw.server.domain.category.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductTemplateFieldService fieldService;
    private final AssetRepository assetRepository;
    private final CategoryRepository categoryRepository;

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
        Product saved = productRepository.save(product);
        // 能源品类专业参数字段（充电桩/光伏/储能）由品类模板自动带入，无需逐个商品手工重填；
        // 分类解析不到或复制失败一律静默跳过 —— 绝不能因为模板套用让商品发布失败。
        applyCategoryTemplate(saved.getId(), req.category());
        return toView(saved);
    }

    /**
     * 按商品的分类名/分类码解析出品类 id 并复制其字段模板到该商品。
     *
     * <p>{@code Product.category} 存的是分类名称或层级码（String），没有 categoryId 字段，
     * 因此这里用名称或 code 反查分类树；解析不到（分类不存在 / 未填分类 / 分类已删除）时静默返回。
     * 分类树规模很小（百级），内存过滤即可，避免为此新增仓储方法。
     */
    private void applyCategoryTemplate(Long productId, String category) {
        if (productId == null || category == null || category.isBlank()) {
            return;
        }
        Long categoryId = resolveCategoryId(category.trim());
        if (categoryId == null) {
            log.debug("产品 {} 的分类 [{}] 未匹配到品类，跳过字段模板套用", productId, category);
            return;
        }
        try {
            int copied = fieldService.copyFromCategory(productId, categoryId);
            log.debug("产品 {} 从品类 {} 复制模板字段 {} 条", productId, categoryId, copied);
        } catch (RuntimeException e) {
            // 模板套用属于发布流程的增强，失败不得回滚已创建的商品（现有发布流程不可破坏）。
            log.warn("产品 {} 从品类 {} 复制模板字段失败，已跳过", productId, categoryId, e);
        }
    }

    /** 按分类名称或层级码匹配未删除分类，返回其 id；匹配不到返回 null。 */
    private Long resolveCategoryId(String categoryKey) {
        return categoryRepository.findByTenantIdAndDeletedFalseOrderBySortNoAsc(1L).stream()
                .filter(c -> categoryKey.equals(c.getCode()) || categoryKey.equals(c.getName()))
                .map(Category::getId)
                .findFirst()
                .orElse(null);
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
