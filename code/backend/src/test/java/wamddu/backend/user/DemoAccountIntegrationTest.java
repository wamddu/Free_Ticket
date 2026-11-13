package wamddu.backend.user;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import wamddu.backend.global.exception.ApiException;
import wamddu.backend.global.security.JwtProvider;
import wamddu.backend.user.domain.Role;
import wamddu.backend.user.domain.User;
import wamddu.backend.user.domain.UserStatus;
import wamddu.backend.user.repository.UserRepository;
import wamddu.backend.user.service.PasswordRecoveryService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The demo accounts' passwords are published in the README, so anyone can sign in with them.
 * They may browse, book and pay (Toss test mode) but must not lock other visitors out, wipe data
 * or read real members' personal data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo_account_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        // Upper case on purpose: matching must ignore case like login does.
        "app.demo.account-emails=Demo-User@example.test, demo-admin@example.test"
})
@DirtiesContext
class DemoAccountIntegrationTest {
    static final String PASSWORD = "Original-pass1";

    @Value("${local.server.port}") int port;
    @Autowired JwtProvider jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired PasswordRecoveryService recovery;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    Long demoUser;
    Long demoAdmin;
    Long realUser;
    Long realAdmin;

    record Result(int status, JsonNode body) {
        String code() {
            return body == null ? null : body.path("code").asString();
        }
    }

    @BeforeEach
    void setUp() {
        String run = String.valueOf(System.nanoTime());
        demoUser = save("demo-user@example.test", "Demo", null, Role.USER);
        demoAdmin = save("demo-admin@example.test", "DemoAdmin", null, Role.ADMIN);
        realUser = save("real.person." + run + "@example.test", "홍길동", "010" + run.substring(run.length() - 8), Role.USER);
        realAdmin = save("owner." + run + "@example.test", "Owner", null, Role.ADMIN);
    }

    Long save(String email, String name, String phone, Role role) {
        return new TransactionTemplate(manager).execute(s -> {
            User existing = users.findByEmail(email);
            if (existing != null) {
                existing.setPassword(encoder.encode(PASSWORD));
                existing.setStatus(UserStatus.ACTIVE);
                return existing.getId();
            }
            User user = User.builder().username(name).password(encoder.encode(PASSWORD)).email(email)
                    .phonenumber(phone).customerKey("c_" + System.nanoTime())
                    .role(role).status(UserStatus.ACTIVE).build();
            em.persist(user);
            return user.getId();
        });
    }

    Result call(Long userId, String method, String path, Object body) throws Exception {
        User user = users.findById(userId).orElseThrow();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt.generateJwtToken(userId, user.getRole().name()))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body().isBlank() ? null : json.readTree(response.body()));
    }

    User reload(Long id) {
        return users.findById(id).orElseThrow();
    }

    @Test
    void demoUserCannotChangePasswordProfileOrDeleteTheAccount() throws Exception {
        Result password = call(demoUser, "PATCH", "/api/users/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "Another-pass1"));
        Result profile = call(demoUser, "POST", "/api/users/me", Map.of("username", "Hacked"));
        Result delete = call(demoUser, "DELETE", "/api/users/me", Map.of("password", PASSWORD));

        for (Result result : List.of(password, profile, delete)) {
            assertThat(result.status()).isEqualTo(403);
            assertThat(result.code()).isEqualTo("DEMO_ACCOUNT_READ_ONLY");
        }
        User after = reload(demoUser);
        assertThat(encoder.matches(PASSWORD, after.getPassword())).isTrue();
        assertThat(after.getUsername()).isEqualTo("Demo");
        assertThat(after.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void demoUserCanStillReadOwnInfoAndOtherMembersAreUnaffected() throws Exception {
        assertThat(call(demoUser, "GET", "/api/auth/me", null).status()).isEqualTo(200);

        Result profile = call(realUser, "POST", "/api/users/me", Map.of("username", "Changed"));
        assertThat(profile.status()).isEqualTo(200);
        assertThat(reload(realUser).getUsername()).isEqualTo("Changed");
    }

    @Test
    void demoAccountPasswordCannotBeResetByEmail() {
        assertThatThrownBy(() -> recovery.reset("DEMO-USER@example.test", "a".repeat(43), "Another-pass1", "Another-pass1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DEMO_ACCOUNT_READ_ONLY"));
        assertThat(encoder.matches(PASSWORD, reload(demoUser).getPassword())).isTrue();
    }

    @Test
    void demoAdminCanReadButNotChangeAdminData() throws Exception {
        assertThat(call(demoAdmin, "GET", "/api/admin/tables", null).status()).isEqualTo(200);
        assertThat(call(demoAdmin, "GET", "/api/admin/tables/events", null).status()).isEqualTo(200);
        assertThat(call(demoAdmin, "GET", "/api/admin/payment-reconciliation/issues", null).status()).isEqualTo(200);

        Result tableEdit = call(demoAdmin, "PATCH", "/api/admin/tables/users",
                Map.of("changes", List.of(), "creations", List.of(), "deletions", List.of()));
        Result action = call(demoAdmin, "POST", "/api/admin/payment-reconciliation/issues/1/resolve",
                Map.of("reason", "demo"));

        for (Result result : List.of(tableEdit, action)) {
            assertThat(result.status()).isEqualTo(403);
            assertThat(result.code()).isEqualTo("DEMO_ACCOUNT_READ_ONLY");
        }
    }

    @Test
    void demoAdminSeesMemberPersonalDataMasked() throws Exception {
        User member = reload(realUser);
        Map<String, String> masked = userRow(call(demoAdmin, "GET", "/api/admin/tables/users?size=100", null), realUser);

        assertThat(masked.get("email")).startsWith("re***@").endsWith("@example.test")
                .doesNotContain(member.getEmail().substring(0, member.getEmail().indexOf('@')));
        assertThat(masked.get("phonenumber")).isEqualTo("*******" + member.getPhonenumber().substring(7));
        assertThat(masked.get("username")).isEqualTo("홍**");
        assertThat(masked.get("customer_key")).isEqualTo("***");
    }

    @Test
    void realAdminStillSeesAndChangesEverything() throws Exception {
        User member = reload(realUser);
        Map<String, String> row = userRow(call(realAdmin, "GET", "/api/admin/tables/users?size=100", null), realUser);
        assertThat(row.get("email")).isEqualTo(member.getEmail());
        assertThat(row.get("phonenumber")).isEqualTo(member.getPhonenumber());

        // An empty save is rejected as INVALID_UPDATE; what matters is that it reaches the table service.
        Result edit = call(realAdmin, "PATCH", "/api/admin/tables/users",
                Map.of("changes", List.of(), "creations", List.of(), "deletions", List.of()));
        assertThat(edit.status()).isNotEqualTo(403);
        assertThat(edit.code()).isNotEqualTo("DEMO_ACCOUNT_READ_ONLY");
    }

    Map<String, String> userRow(Result result, Long id) {
        assertThat(result.status()).isEqualTo(200);
        JsonNode data = result.body().path("data");
        List<String> columns = new ArrayList<>();
        data.path("columns").forEach(column -> columns.add(column.asString().toLowerCase(java.util.Locale.ROOT)));
        for (JsonNode row : data.path("rows")) {
            if (row.get(columns.indexOf("id")).asString().equals(String.valueOf(id))) {
                Map<String, String> values = new java.util.HashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    values.put(columns.get(i), row.get(i).isNull() ? null : row.get(i).asString());
                }
                return values;
            }
        }
        throw new AssertionError("user " + id + " not in the admin table response");
    }
}
