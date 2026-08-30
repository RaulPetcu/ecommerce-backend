package com.ecommerce.project.service;

import com.ecommerce.project.exceptions.ResourceAlreadyExistsException;
import com.ecommerce.project.exceptions.ResourceNotFoundException;
import com.ecommerce.project.model.AppRole;
import com.ecommerce.project.model.Role;
import com.ecommerce.project.model.User;
import com.ecommerce.project.repositories.RoleRepository;
import com.ecommerce.project.repositories.UserRepository;
import com.ecommerce.project.security.request.SignupRequest;
import com.ecommerce.project.security.response.MessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder encoder;

    // AuthServiceImpl also depends on AuthenticationManager, JwtUtils, and
    // ModelMapper for other methods (login, getCurrentUserDetails, etc.),
    // but register() never touches them, so they're wired manually below
    // rather than pulled in via @InjectMocks to keep this class focused.
    private AuthServiceImpl authService;

    private SignupRequest signupRequest;
    private Role userRole;
    private Role adminRole;
    private Role sellerRole;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl();
        authService.userRepository = userRepository;
        authService.roleRepository = roleRepository;
        authService.encoder = encoder;

        signupRequest = new SignupRequest();
        signupRequest.setUsername("raul");
        signupRequest.setEmail("raul@example.com");
        signupRequest.setPassword("plainPassword");

        userRole = new Role(AppRole.ROLE_USER);
        adminRole = new Role(AppRole.ROLE_ADMIN);
        sellerRole = new Role(AppRole.ROLE_SELLER);
    }

    @Nested
    class DuplicateChecks {

        @Test
        void register_whenUsernameAlreadyExists_throwsResourceAlreadyExistsException() {
            when(userRepository.existsByUserName("raul")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(signupRequest))
                    .isInstanceOf(ResourceAlreadyExistsException.class);

            verify(userRepository, never()).existsByEmail(anyString());
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        void register_whenEmailAlreadyExists_throwsResourceAlreadyExistsException() {
            when(userRepository.existsByUserName("raul")).thenReturn(false);
            when(userRepository.existsByEmail("raul@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(signupRequest))
                    .isInstanceOf(ResourceAlreadyExistsException.class);

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        void register_checksUsernameBeforeEmail() {
            // Both are duplicated; username check should short-circuit first.
            when(userRepository.existsByUserName("raul")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(signupRequest))
                    .isInstanceOf(ResourceAlreadyExistsException.class);

            verify(userRepository, never()).existsByEmail(anyString());
        }
    }

    @Nested
    class RoleAssignment {

        @BeforeEach
        void noDuplicates() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
        }

        @Test
        void register_whenNoRolesProvided_assignsDefaultUserRole() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");
            signupRequest.setRole(null);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(userRole));

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getRoles()).containsExactly(userRole);
        }

        @Test
        void register_whenAdminRoleRequested_assignsAdminRole() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");

            signupRequest.setRole(Set.of("admin"));
            when(roleRepository.findByRoleName(AppRole.ROLE_ADMIN)).thenReturn(Optional.of(adminRole));

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getRoles()).containsExactly(adminRole);
        }

        @Test
        void register_whenSellerRoleRequested_assignsSellerRole() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");

            signupRequest.setRole(Set.of("seller"));
            when(roleRepository.findByRoleName(AppRole.ROLE_SELLER)).thenReturn(Optional.of(sellerRole));

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getRoles()).containsExactly(sellerRole);
        }

        @Test
        void register_whenUnrecognizedRoleRequested_fallsBackToUserRole() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");

            signupRequest.setRole(Set.of("not_a_real_role"));
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(userRole));

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getRoles()).containsExactly(userRole);
        }

        @Test
        void register_whenMultipleRolesRequested_assignsAllOfThem() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");

            signupRequest.setRole(new HashSet<>(Set.of("admin", "seller")));
            when(roleRepository.findByRoleName(AppRole.ROLE_ADMIN)).thenReturn(Optional.of(adminRole));
            when(roleRepository.findByRoleName(AppRole.ROLE_SELLER)).thenReturn(Optional.of(sellerRole));

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getRoles())
                    .containsExactlyInAnyOrder(adminRole, sellerRole);
        }

        @Test
        void register_whenRoleNotFoundInDatabase_throwsResourceNotFoundException() {
            signupRequest.setRole(null);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(signupRequest))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    class SuccessPath {

        @BeforeEach
        void noDuplicates() {
            when(userRepository.existsByUserName(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            signupRequest.setRole(null);
            when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(userRole));
        }

        @Test
        void register_encodesPasswordBeforeSaving() {
            when(encoder.encode("plainPassword")).thenReturn("encodedPassword");

            authService.register(signupRequest);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());

            assertThat(userCaptor.getValue().getPassword()).isEqualTo("encodedPassword");
            verify(encoder, times(1)).encode("plainPassword");
        }

        @Test
        void register_onSuccess_returnsOkResponseWithConfirmationMessage() {
            when(encoder.encode(anyString())).thenReturn("encodedPassword");

            ResponseEntity<MessageResponse> response = authService.register(signupRequest);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody().getMessage()).isEqualTo("User registered successfully!");
        }
    }
}