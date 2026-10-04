package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.service.ClipboardHistoryService;
import com.acme.clipcascade.service.UserService;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:clipcascade-privacy-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ClipboardPrivacyAndSessionTests {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate database;

    @Autowired
    private UserService users;

    @Autowired
    private ClipboardHistoryService history;

    @Test
    void anonymousMonitoringDoesNotCreateCookiesOrStoredSessions() throws Exception {
        long before = sessionCount();
        HttpClient anonymous = HttpClient.newHttpClient();

        for (int attempt = 0; attempt < 3; attempt++) {
            for (String path : new String[] { "/health", "/ping" }) {
                var response = get(anonymous, path);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            }
        }

        assertThat(sessionCount()).isEqualTo(before);
    }

    @Test
    void csrfLoginStillStoresAuthenticationAndAnExistingSessionRemainsUsable() throws Exception {
        TestHttpClient login = TestHttpClient.login(users, port, RoleConstants.USER);
        var sessionCookie = login.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.getName())).findFirst().orElseThrow();
        assertThat(sessionCookie.isHttpOnly()).isTrue();
        assertThat(database.queryForObject("""
                SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES attributes
                JOIN SPRING_SESSION sessions ON sessions.PRIMARY_ID = attributes.SESSION_PRIMARY_ID
                WHERE sessions.PRINCIPAL_NAME = ? AND attributes.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'
                """, Long.class, login.username())).isEqualTo(1L);

        // A fresh client has no in-memory authentication and only the saved session cookie.
        HttpClient reconnected = HttpClient.newHttpClient();
        String savedCookie = "JSESSIONID=" + sessionCookie.getValue();
        var identity = reconnected.send(HttpRequest.newBuilder(url("/whoami"))
                .header("Cookie", savedCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(identity.statusCode()).isEqualTo(200);
        assertThat(identity.body()).contains(login.username());

        assertThat(get(login.client(), "/health").statusCode()).isEqualTo(200);
        assertThat(get(login.client(), "/whoami").body()).contains(login.username());
    }

    @Test
    void clipboardPreviewsAndDownloadsArePrivateAndNotStoredByCaches() throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        byte[] png = png();
        var entry = history.recordClipboard(owner.username(), "privacy-test-device",
                new ClipboardData(Base64.getEncoder().encodeToString(png), "image", null));

        for (String action : new String[] { "preview", "download" }) {
            var response = owner.client().send(HttpRequest.newBuilder(
                    url("/admin/clipboard-history/" + entry.getId() + "/" + action)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(png);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .contains("private", "no-store").doesNotContain("public", "86400");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("image/png");
        }
    }

    @Test
    void anotherAccountCannotReadClipboardPreviewsOrDownloads() throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var entry = history.recordClipboard(owner.username(), "privacy-test-device",
                new ClipboardData(Base64.getEncoder().encodeToString(png()), "image", null));
        TestHttpClient other = TestHttpClient.login(users, port, RoleConstants.ADMIN);

        for (String action : new String[] { "preview", "download" }) {
            String path = "/admin/clipboard-history/" + entry.getId() + "/" + action;
            assertThat(get(other.client(), path).statusCode()).isEqualTo(404);
            assertThat(get(HttpClient.newHttpClient(), path).statusCode()).isEqualTo(302);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private long sessionCount() {
        return database.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

}
