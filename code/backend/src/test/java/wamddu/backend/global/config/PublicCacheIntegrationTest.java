package wamddu.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import wamddu.backend.global.security.JwtProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CloudFront caches whatever the server marks public, so only successful anonymous event reads may carry it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:public_cache_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@DirtiesContext
class PublicCacheIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JwtProvider jwt;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void publicEventReadsMayBeCachedForTwoSeconds() throws Exception {
        HttpResponse<String> response = get("/api/events/whats-hot", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("max-age=2, public");
    }

    @Test
    void errorsAreNeverMarkedPublic() throws Exception {
        HttpResponse<String> response = get("/api/events/987654321", null);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).doesNotContain("public").contains("no-cache");
    }

    @Test
    void personalDataIsNeverMarkedPublic() throws Exception {
        HttpResponse<String> response = get("/api/orders/me/reservations", jwt.generateJwtToken(1L, "USER"));
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).doesNotContain("public").contains("no-cache");
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
