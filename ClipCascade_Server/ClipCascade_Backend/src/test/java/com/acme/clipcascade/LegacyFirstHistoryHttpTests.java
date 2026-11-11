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
        "spring.datasource.url=jdbc:h2:mem:clipcascade-legacy-first-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class LegacyFirstHistoryHttpTests {
    @LocalServerPort private int port;
    @Autowired private UserService users;
    @Autowired private ClipboardHistoryService history;
    @Autowired private ClipboardIngestionService ingestion;
    @Autowired private BinaryClipboardController binary;
    @Autowired private ObjectMapper json;

    @Autowired private com.acme.clipcascade.repo.ClipboardHistoryRepo repository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void legacyFirstMatchingTextUpgradesOnlyFormatAndPreservesThePinnedRow() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        String content = "A".repeat(200);
        var metadata = metadata();
        var source = source();
        ingestion.recordLegacy(owner.username(), new ClipboardData(content, "text", metadata), source);
        var before = history.getRecentHistory(owner.username()).getFirst();
        assertThat(history.setPinned(before.getId(), owner.username(), true)).isTrue();
        sendClear(owner.username(), content, metadata, source);
        sendClear(owner.username(), content, metadata, source);
        var after = history.getRecentHistory(owner.username()).getFirst();
        var download = bytes(owner, "/admin/clipboard-history/" + after.getId() + "/download");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(download.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
        assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("private", "no-store");
        assertThat(history.countForUser(owner.username())).isEqualTo(1);
        assertThat(after.getPayloadFormat()).isEqualTo("utf8");
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getUsername()).isEqualTo(before.getUsername());
        assertThat(after.getDeviceId()).isEqualTo(before.getDeviceId());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getPayload()).isEqualTo(before.getPayload());
        assertThat(after.getPayloadSize()).isEqualTo(before.getPayloadSize());
        assertThat(after.isPinned()).isTrue();
        // An unrelated historical row with identical text is never backfilled.
        var historical = history.recordClipboard(owner.username(), "old-device",
                new ClipboardData(content, "text", Map.of()));
        sendClear(owner.username(), content, metadata, source);
        assertThat(history.getHistoryItem(historical.getId(), owner.username()).getPayloadFormat()).isEqualTo("legacy");
    }

    @Test
    void differentContentAndOpaqueEvidenceCannotRewriteTheLegacyRow() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var metadata = metadata();
        var source = source();
        String content = "A".repeat(200);
        ingestion.recordLegacy(owner.username(), new ClipboardData(content, "text", metadata), source);
        var before = history.getRecentHistory(owner.username()).getFirst();
        sendClear(owner.username(), "B".repeat(200), metadata, source);
        byte[] opaque = frame(Map.of("type", "text", "encrypted", true, "metadata", metadata), new byte[] {1,2,3});
        binary.send(() -> owner.username(), opaque, source);
        var after = history.getHistoryItem(before.getId(), owner.username());
        assertThat(after.getPayloadFormat()).isEqualTo("legacy");
        assertThat(after.getPayload()).isEqualTo(content);
        assertThat(history.countForUser(owner.username())).isEqualTo(1);
        assertThat(bytes(owner, "/admin/clipboard-history/" + after.getId() + "/download").body()).isEqualTo(new byte[150]);
    }

    @Test
    void opaqueFirstAndNonTextRowsCannotBeUpgradedByClearText() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var metadata = metadata();
        var source = source();
        byte[] opaque = frame(Map.of("type", "text", "encrypted", true, "metadata", metadata), new byte[] {1,2,3});
        binary.send(() -> owner.username(), opaque, source);
        var before = history.getRecentHistory(owner.username()).getFirst();
        sendClear(owner.username(), "A".repeat(200), metadata, source);
        var after = history.getHistoryItem(before.getId(), owner.username());
        assertThat(after.getPayloadFormat()).isEqualTo("ccb1");
        assertThat(after.getPayload()).isEqualTo(before.getPayload());
        assertThat(bytes(owner, "/admin/clipboard-history/" + after.getId() + "/download").body()).isEqualTo(opaque);
        var imageMetadata = metadata();
        ingestion.recordLegacy(owner.username(), new ClipboardData("A".repeat(200), "image", imageMetadata), source);
        var image = history.getRecentHistory(owner.username()).stream().filter(h -> h.getPayloadType().equals("image")).findFirst().orElseThrow();
        sendClear(owner.username(), "A".repeat(200), imageMetadata, source);
        assertThat(history.getHistoryItem(image.getId(), owner.username()).getPayloadFormat()).isEqualTo("legacy");
    }

    @Test
    void anotherOwnerOrSessionCannotUpgradeTheFirstSessionsRow() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var other = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        String content = "A".repeat(200);
        var metadata = metadata();
        var first = source();
        ingestion.recordLegacy(owner.username(), new ClipboardData(content, "text", metadata), first);
        var before = history.getRecentHistory(owner.username()).getFirst();
        sendClear(other.username(), content, metadata, first);
        sendClear(owner.username(), content, metadata, source());
        assertThat(history.getHistoryItem(before.getId(), owner.username()).getPayloadFormat()).isEqualTo("legacy");
        assertThat(history.countForUser(owner.username())).isEqualTo(2);
        assertThat(history.countForUser(other.username())).isEqualTo(1);
        assertThat(bytes(other, "/admin/clipboard-history/" + before.getId() + "/download").statusCode()).isEqualTo(404);
    }

    @Test
    void formatUpdateCannotOverwriteAPinCommittedAfterAnEntityWasRead() throws Exception {
        var owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        var entry = history.recordClipboard(owner.username(), "preserved-device",
                new ClipboardData("A".repeat(200), "text", Map.of()));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            var stale = repository.findById(entry.getId()).orElseThrow();
            assertThat(stale.isPinned()).isFalse();
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> history.setPinned(entry.getId(), owner.username(), true)).get()).isTrue();
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(stale.isPinned()).isFalse();
            assertThat(history.upgradeLegacyText(entry.getId(), owner.username(), entry.getPayload())).isTrue();
        });
        var after = history.getHistoryItem(entry.getId(), owner.username());
        assertThat(after.isPinned()).isTrue();
        assertThat(after.getPayloadFormat()).isEqualTo("utf8");
        assertThat(after.getCreatedAt()).isEqualTo(entry.getCreatedAt());
        assertThat(after.getDeviceId()).isEqualTo(entry.getDeviceId());
        assertThat(after.getPayload()).isEqualTo(entry.getPayload());
    }

    @Test
    void atomicGuardRejectsWrongOwnerPayloadTypeFormatAndDeletedRows() {
        var row = history.recordClipboard("guard-owner", "device", new ClipboardData("exact", "text", Map.of()));
        assertThat(history.upgradeLegacyText(row.getId(), "other-owner", "exact")).isFalse();
        assertThat(history.upgradeLegacyText(row.getId(), "guard-owner", "EXACT")).isFalse();
        assertThat(history.upgradeLegacyText(row.getId(), "guard-owner", "exact ")).isFalse();
        assertThat(history.upgradeLegacyText(row.getId(), "guard-owner", "exact")).isTrue();
        assertThat(history.upgradeLegacyText(row.getId(), "guard-owner", "exact")).isFalse();
        var image = history.recordClipboard("guard-owner", "device", new ClipboardData("exact", "image", Map.of()));
        assertThat(history.upgradeLegacyText(image.getId(), "guard-owner", "exact")).isFalse();
        var opaque = history.recordClipboard("guard-owner", "device", new ClipboardData("ZXhhY3Q=", "text", Map.of()), "ccb1");
        assertThat(history.upgradeLegacyText(opaque.getId(), "guard-owner", "ZXhhY3Q=")).isFalse();
        history.deleteByIds(List.of(row.getId()), "guard-owner");
        assertThat(history.upgradeLegacyText(row.getId(), "guard-owner", "exact")).isFalse();
    }

    private Map<String, Object> metadata() {
        return Map.of("historyTransferId", UUID.randomUUID().toString(), "contentSizeBytes", 200, "deviceId", "legacy-first-device");
    }

    private void sendClear(String username, String content, Map<String, Object> metadata, StompHeaderAccessor source) throws Exception {
        byte[] clear = frame(Map.of("type", "text", "encrypted", false, "metadata", metadata),
                frame(Map.of("type", "text"), content.getBytes(StandardCharsets.UTF_8)));
        binary.send(() -> username, clear, source);
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
