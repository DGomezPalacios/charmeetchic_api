package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Acceso a datos de usuarios. */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Busca por el UID de Firebase (claim {@code sub}), no por el id interno. */
    Optional<User> findByUserId(String userId);
}
