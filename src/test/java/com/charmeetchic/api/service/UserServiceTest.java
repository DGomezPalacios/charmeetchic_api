package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.User;
import com.charmeetchic.api.enums.Role;
import com.charmeetchic.api.repository.UserRepository;
import com.charmeetchic.api.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sincronización del usuario local con lo que dice el token de Firebase en cada petición.
 *
 * <p>Lo crítico son los roles: si a alguien se le quita el custom claim {@code admin} en Firebase, la copia local
 * no puede seguir diciendo ADMIN.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService service;

    @Test
    void firstLogin_registersUserWithTokenRoles() {
        when(userRepository.findByUserId("uid-1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User created = service.syncUser(principal("ana@charme.cl", "Ana", Role.USER));

        assertThat(created.getUserId()).isEqualTo("uid-1");
        assertThat(created.getRoles()).containsExactly(Role.USER);
        assertThat(created.getLastLogin()).isNotNull();
    }

    @Test
    void adminClaimRevokedInFirebase_removesAdminRoleLocally() {
        User stored = stored(EnumSet.of(Role.USER, Role.ADMIN), LocalDateTime.now());
        when(userRepository.findByUserId("uid-1")).thenReturn(Optional.of(stored));

        User synced = service.syncUser(principal("ana@charme.cl", "Ana", Role.USER));

        assertThat(synced.getRoles()).containsExactly(Role.USER);
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminClaimGrantedInFirebase_addsAdminRoleLocally() {
        User stored = stored(EnumSet.of(Role.USER), LocalDateTime.now());
        when(userRepository.findByUserId("uid-1")).thenReturn(Optional.of(stored));

        User synced = service.syncUser(principal("ana@charme.cl", "Ana", Role.USER, Role.ADMIN));

        assertThat(synced.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void changedEmailAndName_areUpdated_butMissingOnesDoNotEraseStoredData() {
        User stored = stored(EnumSet.of(Role.USER), LocalDateTime.now());
        when(userRepository.findByUserId("uid-1")).thenReturn(Optional.of(stored));

        service.syncUser(principal("nuevo@charme.cl", "Ana María", Role.USER));
        assertThat(stored.getEmail()).isEqualTo("nuevo@charme.cl");
        assertThat(stored.getName()).isEqualTo("Ana María");

        // Usuario anónimo / token sin email ni nombre: no se borran los datos ya guardados.
        service.syncUser(principal(null, null, Role.USER));
        assertThat(stored.getEmail()).isEqualTo("nuevo@charme.cl");
        assertThat(stored.getName()).isEqualTo("Ana María");
    }

    @Test
    void lastLogin_isOnlyRewrittenAfterTheRefreshInterval() {
        LocalDateTime recent = LocalDateTime.now().minusMinutes(1);
        User fresh = stored(EnumSet.of(Role.USER), recent);
        when(userRepository.findByUserId("uid-1")).thenReturn(Optional.of(fresh));

        service.syncUser(principal("ana@charme.cl", "Ana", Role.USER));
        assertThat(fresh.getLastLogin()).isEqualTo(recent);

        LocalDateTime old = LocalDateTime.now().minus(UserService.LAST_LOGIN_REFRESH).minusMinutes(1);
        fresh.setLastLogin(old);
        service.syncUser(principal("ana@charme.cl", "Ana", Role.USER));
        assertThat(fresh.getLastLogin()).isAfter(old);
    }

    private static AuthenticatedUser principal(String email, String name, Role... roles) {
        return new AuthenticatedUser("uid-1", email, name, Set.of(roles));
    }

    private static User stored(Set<Role> roles, LocalDateTime lastLogin) {
        return User.builder()
                .userId("uid-1")
                .email("ana@charme.cl")
                .name("Ana")
                .roles(EnumSet.copyOf(roles))
                .lastLogin(lastLogin)
                .build();
    }
}
