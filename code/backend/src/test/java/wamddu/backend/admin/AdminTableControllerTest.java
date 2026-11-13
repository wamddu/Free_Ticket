package wamddu.backend.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import wamddu.backend.global.exception.ApiExceptionHandler;
import wamddu.backend.user.domain.Role;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.domain.UserStatus;
import wamddu.backend.user.repository.UserRepository;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminTableControllerTest {
    private final AdminTableService service = mock(AdminTableService.class);
    private final UserRepository users = mock(UserRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminTableController(service, users, new wamddu.backend.user.service.DemoAccounts("")))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void login(Role currentRole, UserStatus status) {
        // Token still says ADMIN; authorization must use the current database role.
        var principal = new org.springframework.security.core.userdetails.User("1", "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(users.findById(1L)).thenReturn(Optional.of(User.builder()
                .id(1L).role(currentRole).status(status).build()));
    }

    @Test
    void adminCanListAndReadTables() throws Exception {
        login(Role.ADMIN, UserStatus.ACTIVE);
        when(service.tables()).thenReturn(List.of("users"));
        when(service.read("users", 0, 100)).thenReturn(new AdminTableService.TablePage(
                "users", List.of("id"), List.of(List.of("1")), 1, 0, 100));
        mvc.perform(get("/api/admin/tables")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("users"));
        mvc.perform(get("/api/admin/tables/users")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0][0]").value("1"));
    }

    @Test
    void revokedAdminCannotListOrReadAnyTable() throws Exception {
        for (Role role : List.of(Role.USER, Role.DIRECTOR)) {
            login(role, UserStatus.ACTIVE);
            mvc.perform(get("/api/admin/tables")).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/tables/users")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void deletedAdminCannotReadTables() throws Exception {
        login(Role.ADMIN, UserStatus.DELETED);
        mvc.perform(get("/api/admin/tables/users")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousCannotReadTables() throws Exception {
        mvc.perform(get("/api/admin/tables/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void onlyCurrentActiveAdminCanUpdateTables() throws Exception {
        String body = """
                {"changes":[{"id":"1","originalValues":{"username":"before"},"values":{"username":"after"}}]}
                """;
        mvc.perform(patch("/api/admin/tables/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (Role role : List.of(Role.USER, Role.DIRECTOR)) {
            login(role, UserStatus.ACTIVE);
            mvc.perform(patch("/api/admin/tables/users").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        login(Role.ADMIN, UserStatus.DELETED);
        mvc.perform(patch("/api/admin/tables/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);

        login(Role.ADMIN, UserStatus.ACTIVE);
        var changes = List.of(new AdminTableService.RowChange("1", java.util.Map.of("username", "before"),
                java.util.Map.of("username", "after")));
        when(service.save("users", changes, null, null, 0, 100)).thenReturn(new AdminTableService.TablePage(
                "users", List.of("id", "username"), List.of(List.of("1", "after")), 1, 0, 100));
        mvc.perform(patch("/api/admin/tables/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0][1]").value("after"));
    }
}
