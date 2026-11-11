package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.test.context.ActiveProfiles;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.controller.BinaryClipboardController;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.service.ClipboardHistoryService;
import com.acme.clipcascade.service.ClipboardIngestionService;
import com.acme.clipcascade.service.P2SDeliveryService;
import com.acme.clipcascade.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:clipcascade-binary-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class BinaryHistoryHttpTests {
    @LocalServerPort private int port;
    @Autowired private UserService users;
    @Autowired private ClipboardHistoryService history;
    @Autowired private ClipboardIngestionService ingestion;
    @Autowired private BinaryClipboardController binary;
    @Autowired private ObjectMapper json;

    @ParameterizedTest
    @ValueSource(strings = { "text", "image", "files" })
    void unencryptedBinaryUsesExistingHistoryPreviewAndDownloadFormats(String type) throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        byte[] content = "Synthetic binary clipboard text".getBytes(StandardCharsets.UTF_8);
        if (type.equals("image")) {
            var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            var out = new ByteArrayOutputStream();
            assertThat(ImageIO.write(image, "png", out)).isTrue();
            content = out.toByteArray();
        }
        Map<String, Object> innerHeader = new HashMap<>();
        innerHeader.put("type", type);
        if (type.equals("files")) innerHeader.put("files", List.of(List.of("sample.txt", content.length)));
        String transfer = UUID.randomUUID().toString();
        var metadata = Map.<String, Object>of("deviceId", "binary-device", "historyTransferId", transfer,
                "contentSizeBytes", content.length);
        byte[] frame = frame(Map.of("type", type, "encrypted", false, "metadata", metadata),
                frame(innerHeader, content));
        var source = source();
        binary.send(() -> owner.username(), frame, source);

        var items = history.getRecentHistory(owner.username());
        assertThat(items).hasSize(1);
        var entry = items.getFirst();
        assertThat(entry.getPayloadFormat()).isEqualTo("legacy");
        assertThat(entry.getDeviceId()).isNotBlank();
        if (type.equals("text")) assertThat(entry.getPayload()).isEqualTo(new String(content, StandardCharsets.UTF_8));
        if (type.equals("image")) assertThat(Base64.getDecoder().decode(entry.getPayload())).isEqualTo(content);
        if (type.equals("files")) assertThat(Base64.getDecoder().decode(json.readTree(entry.getPayload()).get("sample.txt").asText()))
                .isEqualTo(content);
        var download = bytes(owner, "/admin/clipboard-history/" + entry.getId() + "/download");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-store");
        if (type.equals("image")) assertThat(bytes(owner, "/admin/clipboard-history/" + entry.getId() + "/preview").body())
                .isEqualTo(content);
        // Paired legacy delivery from the same client has the same logical transfer ID.
        ingestion.recordLegacy(owner.username(), new ClipboardData(entry.getPayload(), type, metadata), source);
        assertThat(history.countForUser(owner.username())).isEqualTo(1);
    }

    @Test
    void encryptedBinaryIsRetainedOpaqueDownloadableAndOwnerScoped() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        byte[] opaque = { 12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 23, 45, 67, 89 };
        byte[] frame = frame(Map.of("type", "files", "encrypted", true, "metadata",
                Map.of("contentSizeBytes", 12, "deviceId", "encrypted-device")), opaque);
        binary.send(() -> owner.username(), frame, source());
        var entry = history.getRecentHistory(owner.username()).getFirst();
        assertThat(entry.getPayloadFormat()).isEqualTo("ccb1");
        assertThat(Base64.getDecoder().decode(entry.getPayload())).isEqualTo(frame);
        assertThat(entry.getPayloadSize()).isEqualTo(frame.length);
        assertThat(entry.getPayloadPreview()).startsWith("[Encrypted files:");
        String path = "/admin/clipboard-history/" + entry.getId();
        var download = bytes(owner, path + "/download");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(frame);
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).contains(".ccb1");
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-store");
        assertThat(bytes(owner, path + "/preview").statusCode()).isEqualTo(415);
        var other = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        assertThat(bytes(other, path + "/download").statusCode()).isEqualTo(404);
        assertThat(bytes(other, path + "/preview").statusCode()).isEqualTo(404);
    }

    @Test
    void legacyFirstPairIsDeduplicatedButDifferentSessionsAreNot() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var metadata = Map.<String, Object>of("historyTransferId", UUID.randomUUID().toString(),
                "contentSizeBytes", 5, "deviceId", "paired-device");
        var source = source();
        ingestion.recordLegacy(owner.username(), new ClipboardData("first", "text", metadata), source);
        byte[] frame = frame(Map.of("type", "text", "encrypted", false, "metadata", metadata),
                frame(Map.of("type", "text"), "first".getBytes(StandardCharsets.UTF_8)));
        binary.send(() -> owner.username(), frame, source);
        assertThat(history.countForUser(owner.username())).isEqualTo(1);
        binary.send(() -> owner.username(), frame, source());
        assertThat(history.countForUser(owner.username())).isEqualTo(2);
    }

    private StompHeaderAccessor source() {
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId(UUID.randomUUID().toString());
        headers.setSessionAttributes(new HashMap<>());
        headers.setNativeHeader(P2SDeliveryService.BINARY_HEADER, "ccb1");
        headers.setNativeHeader(P2SDeliveryService.RECIPIENTS_HEADER, "synthetic-recipient");
        P2SDeliveryService.recordCapabilities(headers);
        return headers;
    }

    private byte[] frame(Map<String, Object> header, byte[] payload) throws Exception {
        byte[] encoded = json.writeValueAsBytes(header);
        return ByteBuffer.allocate(8 + encoded.length + payload.length).putInt(0x43434231)
                .putInt(encoded.length).put(encoded).put(payload).array();
    }

    private HttpResponse<byte[]> bytes(TestHttpClient browser, String path) throws Exception {
        return browser.client().send(HttpRequest.newBuilder(browser.url(path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
