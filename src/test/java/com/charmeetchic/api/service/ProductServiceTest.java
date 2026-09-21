package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.ProductDTO;
import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.repository.CategoryRepository;
import com.charmeetchic.api.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final int LOW_STOCK_THRESHOLD = 5;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;

    private ProductService service;
    private Category category;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, LOW_STOCK_THRESHOLD);
        category = Category.builder().id(1L).name("Collares").build();
    }

    // ------------------------------------------------------------------ lectura

    @Test
    void getProductById_returnsActiveProduct() {
        when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, "COL-001", 10)));

        ProductDTO result = service.getProductById(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.categoryName()).isEqualTo("Collares");
    }

    @Test
    void getProductById_unknownId_throwsNotFound() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getProductById_inactiveProduct_isHiddenFromPublic() {
        Product inactive = product(5L, "COL-001", 10);
        inactive.setActive(false);
        when(productRepository.findById(5L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.getProductById(5L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllProducts_returnsOnlyActiveMappedToDto() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findByActive(true, pageable))
                .thenReturn(new PageImpl<>(List.of(product(1L, "A", 3), product(2L, "B", 4)), pageable, 2));

        Page<ProductDTO> page = service.getAllProducts(pageable);

        assertThat(page.getContent()).extracting(ProductDTO::sku).containsExactly("A", "B");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchProducts_blankKeyword_matchesEverything() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.search("%", 2L, pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.searchProducts("   ", 2L, pageable)).isEmpty();

        verify(productRepository).search("%", 2L, pageable);
    }

    @Test
    void searchProducts_keywordIsTrimmedAndLowercased() {
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.search("%perla%", null, pageable)).thenReturn(Page.empty(pageable));

        service.searchProducts("  PeRLa ", null, pageable);

        verify(productRepository).search("%perla%", null, pageable);
    }

    @Test
    void getLowStockProducts_usesConfiguredThreshold() {
        when(productRepository.findByActiveTrueAndStockLessThanOrderByStockAsc(LOW_STOCK_THRESHOLD))
                .thenReturn(List.of(product(1L, "LOW-1", 2)));

        List<ProductDTO> result = service.getLowStockProducts();

        assertThat(result).extracting(ProductDTO::sku).containsExactly("LOW-1");
    }

    // ------------------------------------------------------------------ creación

    @Test
    void createProduct_normalizesSkuAndDefaultsToActive() {
        when(productRepository.existsBySku("COL-NEW")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(10L);
            p.setVersion(0L);
            return p;
        });

        ProductDTO created = service.createProduct(dto(" col-new ", null));

        assertThat(created.id()).isEqualTo(10L);
        assertThat(created.sku()).isEqualTo("COL-NEW");
        assertThat(created.name()).isEqualTo("Collar Nuevo");
        assertThat(created.active()).isTrue();
        assertThat(created.images()).containsExactly("/x.jpg");
    }

    @Test
    void createProduct_duplicateSku_throwsConflict() {
        when(productRepository.existsBySku("COL-DUP")).thenReturn(true);

        assertThatThrownBy(() -> service.createProduct(dto("col-dup", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COL-DUP");

        verify(productRepository, never()).save(any());
    }

    @Test
    void createProduct_unknownCategory_throwsNotFound() {
        when(productRepository.existsBySku("COL-NEW")).thenReturn(false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProduct(dto("COL-NEW", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ actualización / borrado

    @Test
    void updateProduct_replacesFields() {
        Product existing = product(5L, "OLD-SKU", 10);
        when(productRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuAndIdNot("NEW-SKU", 5L)).thenReturn(false);
        when(productRepository.saveAndFlush(existing)).thenReturn(existing);

        ProductDTO updated = service.updateProduct(5L, dto("new-sku", 3L));

        assertThat(updated.sku()).isEqualTo("NEW-SKU");
        assertThat(existing.getName()).isEqualTo("Collar Nuevo");
        assertThat(existing.getStock()).isEqualTo(10);
        assertThat(existing.getImages()).containsExactly("/x.jpg");
    }

    @Test
    void updateProduct_staleVersion_throwsOptimisticLockFailure() {
        when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, "OLD-SKU", 10)));

        assertThatThrownBy(() -> service.updateProduct(5L, dto("NEW-SKU", 2L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateProduct_skuUsedByAnotherProduct_throwsConflict() {
        when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, "OLD-SKU", 10)));
        when(productRepository.existsBySkuAndIdNot("TAKEN", 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateProduct(5L, dto("taken", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteProduct_isSoftDelete() {
        Product existing = product(5L, "COL-001", 10);
        when(productRepository.findById(5L)).thenReturn(Optional.of(existing));

        service.deleteProduct(5L);

        assertThat(existing.isActive()).isFalse();
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    void deleteProduct_unknownId_throwsNotFound() {
        when(productRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProduct(42L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private Product product(Long id, String sku, int stock) {
        return Product.builder()
                .id(id).name("Collar").description("desc")
                .price(new BigDecimal("100.00")).cost(new BigDecimal("40.00"))
                .sku(sku).stock(stock).category(category)
                .images(new ArrayList<>(List.of("/old.jpg")))
                .active(true).version(3L)
                .build();
    }

    private ProductDTO dto(String sku, Long version) {
        return new ProductDTO(null, " Collar Nuevo ", "desc", new BigDecimal("150.00"), new BigDecimal("60.00"),
                sku, 10, 1L, null, List.of("/x.jpg"), null, null, null, version);
    }
}
