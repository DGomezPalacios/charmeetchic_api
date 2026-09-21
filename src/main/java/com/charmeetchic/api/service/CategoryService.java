package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.CategoryDTO;
import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.repository.CategoryRepository;
import com.charmeetchic.api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD de categorías. Lectura pública; crear, actualizar y borrar es solo ADMIN (regla aplicada en
 * {@code SecurityConfig} y {@code CategoryController}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CategoryDTO> getAllCategories() {
        return categoryRepository.findAll(Sort.by("name")).stream().map(CategoryDTO::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryDTO getCategoryById(Long id) {
        return CategoryDTO.from(findOrThrow(id));
    }

    @Transactional
    public CategoryDTO createCategory(CategoryDTO dto) {
        String name = dto.name().trim();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("Ya existe una categoría llamada '" + name + "'");
        }
        Category saved = categoryRepository.save(Category.builder()
                .name(name)
                .description(dto.description())
                .build());
        log.info("Categoría creada: id={}, name='{}'", saved.getId(), saved.getName());
        return CategoryDTO.from(saved);
    }

    @Transactional
    public CategoryDTO updateCategory(Long id, CategoryDTO dto) {
        Category category = findOrThrow(id);
        String name = dto.name().trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("Ya existe una categoría llamada '" + name + "'");
        }
        category.setName(name);
        category.setDescription(dto.description());
        log.info("Categoría actualizada: id={}, name='{}'", id, name);
        return CategoryDTO.from(categoryRepository.save(category));
    }

    /** Solo se puede borrar una categoría sin productos (activos o no) para no dejar huérfano el histórico. */
    @Transactional
    public void deleteCategory(Long id) {
        Category category = findOrThrow(id);
        if (productRepository.existsByCategoryId(id)) {
            throw new ConflictException("No se puede eliminar la categoría '" + category.getName()
                    + "' porque tiene productos asociados");
        }
        categoryRepository.delete(category);
        log.info("Categoría eliminada: id={}, name='{}'", id, category.getName());
    }

    private Category findOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoría", id));
    }
}
