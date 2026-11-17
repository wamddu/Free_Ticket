package wamddu.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind CloudFront the browser's Origin (the CloudFront address) differs from the Host the server sees (its own
 * address), so Spring treats every login or refresh as cross-origin. The CloudFront address must be allowed by
 * setting, and nothing else may be.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:cdn_origin_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.cors.allowed-origins=https://d1234abcd.cloudfront.net"
})
@DirtiesContext
class CdnOriginIntegrationTest {
    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theConfiguredCdnAddressCanCallTheApi() throws Exception {
        HttpResponse<String> response = refreshFrom("https://d1234abcd.cloudfront.net");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://d1234abcd.cloudfront.net");
    }

    @Test
    void otherSitesAreStillRefused() throws Exception {
        assertThat(refreshFrom("https://evil.example").statusCode()).isEqualTo(403);
    }

    HttpResponse<String> refreshFrom(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/refresh"))
                .header("Origin", origin).POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
