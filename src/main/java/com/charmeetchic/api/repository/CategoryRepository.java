package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a datos de categorías. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
