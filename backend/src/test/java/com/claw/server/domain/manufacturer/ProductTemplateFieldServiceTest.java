package com.claw.server.domain.manufacturer;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.ProductDtos.CreateProductTemplateFieldReq;
import com.claw.server.common.dto.ProductDtos.ProductTemplateFieldView;
import com.claw.server.common.dto.ProductDtos.UpdateProductTemplateFieldReq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductTemplateFieldServiceTest {

    @Mock
    private ProductTemplateFieldRepository fieldRepository;

    @InjectMocks
    private ProductTemplateFieldService service;

    @Test
    void create_success_storesUniqueFieldAndReturnsView() {
        CreateProductTemplateFieldReq req = new CreateProductTemplateFieldReq(
                10L, "max_speed", "最高时速", "number", "km/h", null, true, 1);
        when(fieldRepository.existsByProductIdAndFieldKey(10L, "max_speed")).thenReturn(false);
        when(fieldRepository.save(any(ProductTemplateField.class))).thenAnswer(inv -> {
            ProductTemplateField e = inv.getArgument(0);
            e.setId(99L);
            return e;
        });

        ProductTemplateFieldView view = service.create(req);

        assertNotNull(view);
        assertEquals(10L, view.productId());
        assertEquals("max_speed", view.fieldKey());
        assertEquals("最高时速", view.label());
        assertEquals("number", view.type());
        assertEquals("km/h", view.unit());
        assertTrue(view.required());
        assertEquals(1, view.sortNo());
        verify(fieldRepository).save(any(ProductTemplateField.class));
    }

    @Test
    void create_duplicateKey_throwsBizException() {
        CreateProductTemplateFieldReq req = new CreateProductTemplateFieldReq(
                10L, "max_speed", "最高时速", "number", null, null, false, 0);
        when(fieldRepository.existsByProductIdAndFieldKey(10L, "max_speed")).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.create(req));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
        verify(fieldRepository, never()).save(any());
    }

    @Test
    void create_invalidType_throwsBizException() {
        CreateProductTemplateFieldReq req = new CreateProductTemplateFieldReq(
                10L, "bad", "坏字段", "geometry", null, null, false, 0);

        BizException ex = assertThrows(BizException.class, () -> service.create(req));
        assertEquals(BizException.INVALID_PARAM, ex.getCode());
    }

    @Test
    void list_returnsSortedViews() {
        ProductTemplateField f1 = ProductTemplateField.builder().id(1L).productId(10L)
                .fieldKey("b").label("B").type("text").sortNo(2).build();
        ProductTemplateField f2 = ProductTemplateField.builder().id(2L).productId(10L)
                .fieldKey("a").label("A").type("text").sortNo(1).build();
        when(fieldRepository.findByProductIdOrderBySortNoAsc(10L)).thenReturn(List.of(f2, f1));

        List<ProductTemplateFieldView> views = service.list(10L);

        assertEquals(2, views.size());
        assertEquals("a", views.get(0).fieldKey());
        assertEquals("b", views.get(1).fieldKey());
    }

    @Test
    void update_changesLabelAndType() {
        ProductTemplateField existing = ProductTemplateField.builder().id(5L).productId(10L)
                .fieldKey("k").label("旧").type("text").sortNo(0).build();
        when(fieldRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(fieldRepository.save(any(ProductTemplateField.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProductTemplateFieldReq req = new UpdateProductTemplateFieldReq(
                "新标签", "number", null, null, null, null);
        ProductTemplateFieldView view = service.update(5L, req);

        assertEquals("新标签", view.label());
        assertEquals("number", view.type());
        assertEquals("k", view.fieldKey());
    }

    @Test
    void update_invalidType_throwsBizException() {
        ProductTemplateField existing = ProductTemplateField.builder().id(5L).productId(10L)
                .fieldKey("k").label("旧").type("text").sortNo(0).build();
        when(fieldRepository.findById(5L)).thenReturn(Optional.of(existing));

        UpdateProductTemplateFieldReq req = new UpdateProductTemplateFieldReq(
                null, "geometry", null, null, null, null);
        assertThrows(BizException.class, () -> service.update(5L, req));
    }

    @Test
    void delete_notFound_throwsBizException() {
        when(fieldRepository.existsById(7L)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.delete(7L));
        assertEquals(BizException.NOT_FOUND, ex.getCode());
        verify(fieldRepository, never()).deleteById(any());
    }

    @Test
    void delete_existing_deletes() {
        when(fieldRepository.existsById(7L)).thenReturn(true);

        service.delete(7L);

        verify(fieldRepository).deleteById(7L);
    }
}
