package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.service.ClipboardHistoryService;
import com.acme.clipcascade.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:clipcascade-history-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ClipboardHistoryHttpTests {
    @LocalServerPort
    private int port;
    @Autowired
    private UserService users;
    @Autowired
    private ClipboardHistoryService history;
    @Autowired
    private ObjectMapper json;

    @Test
    void dashboardRendersTheEntireTemplateIncludingItsControls() throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var response = owner.get("/admin/dashboard");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("filter-pinned", "togglePinned", "populateDeviceFilters")
                .endsWith("</html>\n");
    }

    @Test
    void pinningRequiresCsrfAndOwnershipAndSupportsFiltering() throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var entry = history.recordClipboard(owner.username(), "http-device",
                new ClipboardData("synthetic pinned clip", "text", null));
        String path = "/admin/clipboard-history/" + entry.getId() + "/pin";
        assertThat(put(owner, path, "{\"pinned\":true}", false).statusCode()).isEqualTo(403);
        assertThat(put(owner, path, "{}", true).statusCode()).isEqualTo(400);
        assertThat(put(owner, path, "{\"pinned\":null}", true).statusCode()).isEqualTo(400);
        assertThat(put(owner, path, "{\"pinned\":true}", true).statusCode()).isEqualTo(200);
        assertThat(history.getHistoryItem(entry.getId(), owner.username()).isPinned()).isTrue();
        var pinned = json.readTree(owner.get("/admin/clipboard-history?pinned=true").body());
        assertThat(pinned.get("totalElements").asInt()).isEqualTo(1);
        assertThat(pinned.get("content").get(0).get("pinned").asBoolean()).isTrue();
        assertThat(json.readTree(owner.get("/admin/clipboard-history?pinned=false").body())
                .get("totalElements").asInt()).isZero();

        TestHttpClient other = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        assertThat(put(other, path, "{\"pinned\":false}", true).statusCode()).isEqualTo(404);
        TestHttpClient regular = TestHttpClient.login(users, port, RoleConstants.USER);
        assertThat(put(regular, path, "{\"pinned\":false}", true).statusCode()).isEqualTo(403);
        assertThat(history.getHistoryItem(entry.getId(), owner.username()).isPinned()).isTrue();
        assertThat(put(owner, path, "{\"pinned\":false}", true).body()).contains("false");
        assertThat(history.getHistoryItem(entry.getId(), owner.username()).isPinned()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "png", "jpeg", "bmp", "tiff", "dib" })
    void previewsDecodeExistingClipboardImageFormats(String format) throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x2277cc);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format.equals("dib") ? "bmp" : format, encoded)).isTrue();
        byte[] raw = encoded.toByteArray();
        if (format.equals("dib")) raw = Arrays.copyOfRange(raw, 14, raw.length);
        var entry = history.recordClipboard(owner.username(), "http-device",
                new ClipboardData(Base64.getEncoder().encodeToString(raw), "image", null));
        for (String action : new String[] { "preview", "download" }) {
            var response = owner.client().send(HttpRequest.newBuilder(owner.url(
                    "/admin/clipboard-history/" + entry.getId() + "/" + action)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-store");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("image/png");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.body()));
            assertThat(decoded).isNotNull();
            assertThat(decoded.getWidth()).isEqualTo(3);
            assertThat(decoded.getHeight()).isEqualTo(2);
        }
    }

    private HttpResponse<String> put(TestHttpClient browser, String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(browser.url(path)).header("Content-Type", "application/json");
        if (csrf) {
            String token = json.readTree(browser.get("/csrf-token").body()).get("token").asText();
            request.header("X-CSRF-TOKEN", token);
        }
        return browser.client().send(request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
