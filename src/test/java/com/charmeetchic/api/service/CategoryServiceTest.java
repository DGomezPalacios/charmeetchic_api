package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.CategoryDTO;
import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.repository.CategoryRepository;
import com.charmeetchic.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private CategoryService service;

    @Test
    void createCategory_trimsNameAndSaves() {
        when(categoryRepository.existsByNameIgnoreCase("Broches")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(7L);
            return c;
        });

        CategoryDTO created = service.createCategory(new CategoryDTO(null, "  Broches ", "Pasadores", null));

        assertThat(created.id()).isEqualTo(7L);
        assertThat(created.name()).isEqualTo("Broches");
    }

    @Test
    void createCategory_duplicateName_throwsConflict() {
        when(categoryRepository.existsByNameIgnoreCase("Anillos")).thenReturn(true);

        assertThatThrownBy(() -> service.createCategory(new CategoryDTO(null, "Anillos", null, null)))
                .isInstanceOf(ConflictException.class);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateCategory_nameTakenByAnother_throwsConflict() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(Category.builder().id(1L).name("Collares").build()));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Anillos", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateCategory(1L, new CategoryDTO(null, "Anillos", null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteCategory_withProducts_throwsConflict() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(Category.builder().id(1L).name("Collares").build()));
        when(productRepository.existsByCategoryId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCategory(1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Collares");

        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteCategory_empty_deletesIt() {
        Category category = Category.builder().id(2L).name("Vacía").build();
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(category));
        when(productRepository.existsByCategoryId(2L)).thenReturn(false);

        service.deleteCategory(2L);

        verify(categoryRepository).delete(category);
    }

    @Test
    void getCategoryById_unknownId_throwsNotFound() {
        when(categoryRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCategoryById(9L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
