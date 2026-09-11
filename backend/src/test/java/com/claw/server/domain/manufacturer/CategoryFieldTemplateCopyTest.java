package com.claw.server.domain.manufacturer;

import com.claw.server.domain.category.CategoryFieldTemplate;
import com.claw.server.domain.category.CategoryFieldTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 品类字段模板复制（{@code ProductTemplateFieldService#copyFromCategory}）单测。
 *
 * <p>纯单测：不连 DB，仓储全部 mock。Mockito 为 strict stubbing，故每个用例只声明真正会走到的 stub。
 */
@ExtendWith(MockitoExtension.class)
class CategoryFieldTemplateCopyTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long CATEGORY_ID = 7L;

    @Mock
    private ProductTemplateFieldRepository fieldRepository;

    @Mock
    private CategoryFieldTemplateRepository categoryFieldTemplateRepository;

    @InjectMocks
    private ProductTemplateFieldService service;

    /** ① 复制条数正确：3 条模板全部落地，且字段属性被原样带走。 */
    @Test
    void copyFromCategory_allFieldsInserted_returnsCountAndCopiesProperties() {
        when(categoryFieldTemplateRepository.findByCategoryIdOrderBySortNoAsc(CATEGORY_ID))
                .thenReturn(List.of(
                        template("rated_power_kw", "额定功率", "number", "kW", null, true, 1),
                        template("gun_count", "充电枪数", "number", "个", null, true, 2),
                        template("gun_type", "枪型", "select", null, "[\"GB/T\",\"CCS2\"]", true, 3)));
        when(fieldRepository.existsByProductIdAndFieldKey(eq(PRODUCT_ID), anyString())).thenReturn(false);
        when(fieldRepository.save(any(ProductTemplateField.class))).thenAnswer(inv -> inv.getArgument(0));

        int copied = service.copyFromCategory(PRODUCT_ID, CATEGORY_ID);

        assertEquals(3, copied);
        ArgumentCaptor<ProductTemplateField> captor = ArgumentCaptor.forClass(ProductTemplateField.class);
        verify(fieldRepository, times(3)).save(captor.capture());
        List<ProductTemplateField> saved = captor.getAllValues();
        assertEquals("rated_power_kw", saved.get(0).getFieldKey());
        assertEquals("额定功率", saved.get(0).getLabel());
        assertEquals("number", saved.get(0).getType());
        assertEquals("kW", saved.get(0).getUnit());
        assertTrue(saved.get(0).isRequired());
        assertEquals(1, saved.get(0).getSortNo());
        assertEquals(PRODUCT_ID, saved.get(0).getProductId());
        assertEquals("[\"GB/T\",\"CCS2\"]", saved.get(2).getOptionsJson());
    }

    /** ② 已存在同 field_key 的跳过：不覆盖、不抛重复键错，只插入缺失的那条。 */
    @Test
    void copyFromCategory_existingFieldKey_skippedWithoutError() {
        when(categoryFieldTemplateRepository.findByCategoryIdOrderBySortNoAsc(CATEGORY_ID))
                .thenReturn(List.of(
                        template("rated_power_kw", "额定功率", "number", "kW", null, true, 1),
                        template("gun_count", "充电枪数", "number", "个", null, true, 2)));
        when(fieldRepository.existsByProductIdAndFieldKey(PRODUCT_ID, "rated_power_kw")).thenReturn(true);
        when(fieldRepository.existsByProductIdAndFieldKey(PRODUCT_ID, "gun_count")).thenReturn(false);
        when(fieldRepository.save(any(ProductTemplateField.class))).thenAnswer(inv -> inv.getArgument(0));

        int copied = service.copyFromCategory(PRODUCT_ID, CATEGORY_ID);

        assertEquals(1, copied);
        ArgumentCaptor<ProductTemplateField> captor = ArgumentCaptor.forClass(ProductTemplateField.class);
        verify(fieldRepository, times(1)).save(captor.capture());
        assertEquals("gun_count", captor.getValue().getFieldKey());
    }

    /** ③ 空模板返回 0，且不落任何字段。 */
    @Test
    void copyFromCategory_emptyTemplate_returnsZeroAndSavesNothing() {
        when(categoryFieldTemplateRepository.findByCategoryIdOrderBySortNoAsc(CATEGORY_ID)).thenReturn(List.of());

        int copied = service.copyFromCategory(PRODUCT_ID, CATEGORY_ID);

        assertEquals(0, copied);
        verify(fieldRepository, never()).save(any(ProductTemplateField.class));
    }

    /** 参数为 null 时直接返回 0，不触碰仓储。 */
    @Test
    void copyFromCategory_nullArgs_returnsZero() {
        assertEquals(0, service.copyFromCategory(null, CATEGORY_ID));
        assertEquals(0, service.copyFromCategory(PRODUCT_ID, null));
        verify(categoryFieldTemplateRepository, never()).findByCategoryIdOrderBySortNoAsc(any());
    }

    /** 仓储抛异常时向上传播，由调用方（ProductService）兜底静默，保证发布流程不被模板套用破坏。 */
    @Test
    void copyFromCategory_repositoryFailure_propagates() {
        when(categoryFieldTemplateRepository.findByCategoryIdOrderBySortNoAsc(CATEGORY_ID))
                .thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.copyFromCategory(PRODUCT_ID, CATEGORY_ID));
    }

    private static CategoryFieldTemplate template(String fieldKey, String label, String type,
                                                  String unit, String optionsJson,
                                                  boolean required, int sortNo) {
        return CategoryFieldTemplate.builder()
                .categoryId(CATEGORY_ID)
                .fieldKey(fieldKey)
                .label(label)
                .type(type)
                .unit(unit)
                .optionsJson(optionsJson)
                .required(required)
                .sortNo(sortNo)
                .build();
    }
}
