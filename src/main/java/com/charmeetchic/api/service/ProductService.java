package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.ProductDTO;
import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.repository.CategoryRepository;
import com.charmeetchic.api.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lógica del catálogo de productos.
 *
 * <p>Notas de diseño:
 * <ul>
 *   <li>El catálogo público solo muestra productos {@code active = true}.</li>
 *   <li>{@link #deleteProduct} es un borrado lógico: las órdenes históricas referencian al producto.
 *       Para reactivarlo, un ADMIN hace PUT con {@code "active": true}.</li>
 *   <li>El SKU se normaliza (trim + mayúsculas) para que la unicidad no dependa de cómo se escriba.</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final int lowStockThreshold;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          @Value("${app.inventory.low-stock-threshold:5}") int lowStockThreshold) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.lowStockThreshold = lowStockThreshold;
    }

    /** Catálogo público paginado (solo productos activos). */
    @Transactional(readOnly = true)
    public Page<ProductDTO> getAllProducts(Pageable pageable) {
        return productRepository.findByActive(true, pageable).map(ProductDTO::from);
    }

    /** Un producto activo; los inactivos se comportan como inexistentes para el público. */
    @Transactional(readOnly = true)
    public ProductDTO getProductById(Long id) {
        Product product = findOrThrow(id);
        if (!product.isActive()) {
            throw new ResourceNotFoundException("Producto", id);
        }
        return ProductDTO.from(product);
    }

    /**
     * Búsqueda por texto (en nombre y descripción, sin distinguir mayúsculas) y/o categoría.
     * Ambos filtros son opcionales.
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> searchProducts(String keyword, Long categoryId, Pageable pageable) {
        String pattern = (keyword == null || keyword.isBlank())
                ? "%"
                : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return productRepository.search(pattern, categoryId, pageable).map(ProductDTO::from);
    }

    @Transactional
    public ProductDTO createProduct(ProductDTO dto) {
        String sku = normalizeSku(dto.sku());
        if (productRepository.existsBySku(sku)) {
            throw new ConflictException("Ya existe un producto con SKU '" + sku + "'");
        }
        Category category = categoryRepository.findById(dto.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Categoría", dto.categoryId()));

        Product saved = productRepository.save(Product.builder()
                .name(dto.name().trim())
                .description(dto.description())
                .price(dto.price())
                .cost(dto.cost())
                .sku(sku)
                .stock(dto.stock())
                .category(category)
                .images(dto.images() == null ? new ArrayList<>() : new ArrayList<>(dto.images()))
                .active(dto.active() == null || dto.active())
                .build());

        log.info("Producto creado: id={}, sku={}, stock={}, price={}", saved.getId(), saved.getSku(),
                saved.getStock(), saved.getPrice());
        return ProductDTO.from(saved);
    }

    /**
     * Reemplaza los datos del producto. Si el DTO trae {@code version} y no coincide con la actual,
     * alguien lo modificó mientras se editaba: se responde 409 en lugar de pisar el cambio ajeno.
     */
    @Transactional
    public ProductDTO updateProduct(Long id, ProductDTO dto) {
        Product product = findOrThrow(id);

        if (dto.version() != null && !dto.version().equals(product.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Product.class, id);
        }
        String sku = normalizeSku(dto.sku());
        if (productRepository.existsBySkuAndIdNot(sku, id)) {
            throw new ConflictException("Ya existe otro producto con SKU '" + sku + "'");
        }
        if (!product.getCategory().getId().equals(dto.categoryId())) {
            product.setCategory(categoryRepository.findById(dto.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Categoría", dto.categoryId())));
        }
        if (!product.getStock().equals(dto.stock())) {
            log.info("Stock modificado manualmente: id={}, sku={}, {} -> {}", id, sku, product.getStock(), dto.stock());
        }

        product.setName(dto.name().trim());
        product.setDescription(dto.description());
        product.setPrice(dto.price());
        product.setCost(dto.cost());
        product.setSku(sku);
        product.setStock(dto.stock());
        product.getImages().clear();
        if (dto.images() != null) {
            product.getImages().addAll(dto.images());
        }
        if (dto.active() != null) {
            product.setActive(dto.active());
        }

        // saveAndFlush: fuerza aquí el chequeo de versión y devuelve el DTO con la versión nueva.
        Product saved = productRepository.saveAndFlush(product);
        log.info("Producto actualizado: id={}, sku={}, version={}", id, saved.getSku(), saved.getVersion());
        return ProductDTO.from(saved);
    }

    /** Borrado lógico (ver notas de la clase). Es idempotente. */
    @Transactional
    public void deleteProduct(Long id) {
        Product product = findOrThrow(id);
        if (product.isActive()) {
            product.setActive(false);
            log.info("Producto desactivado: id={}, sku={}", id, product.getSku());
        }
    }

    /** Productos activos con stock por debajo de {@code app.inventory.low-stock-threshold}. */
    @Transactional(readOnly = true)
    public List<ProductDTO> getLowStockProducts() {
        return productRepository.findByActiveTrueAndStockLessThanOrderByStockAsc(lowStockThreshold)
                .stream().map(ProductDTO::from).toList();
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Producto", id));
    }

    private static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }
}
