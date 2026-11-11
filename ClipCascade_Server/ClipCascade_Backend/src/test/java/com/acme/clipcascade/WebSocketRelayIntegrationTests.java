package com.acme.clipcascade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.acme.clipcascade.constants.RoleConstants;
import com.acme.clipcascade.service.DeviceService;
import com.acme.clipcascade.service.P2SDeliveryService;
import com.acme.clipcascade.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real HTTP authentication and STOMP transport. Every payload/account is synthetic. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:clipcascade-websocket-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.p2p.enabled=false" })
class WebSocketRelayIntegrationTests {
    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired DeviceService devices;
    @Autowired SimpUserRegistry registry;
    @Autowired ObjectMapper json;
    private final List<Peer> peers = new ArrayList<>();

    @AfterEach
    void closeClients() {
        for (Peer peer : peers) {
            if (peer.session.isConnected()) peer.session.disconnect();
            peer.client.stop();
        }
    }

    @Test
    void legacyEchoModernFilteringBinaryAndHistoryUseRealAuthenticatedSockets() throws Exception {
        TestHttpClient owner = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        Peer legacy = connect(owner, false);
        Peer modern = connect(owner, true);
        Peer target = connect(owner, true);
        TestHttpClient outsider = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        Peer foreign = connect(outsider, true);

        byte[] legacyBody = json.writeValueAsBytes(Map.of("payload", "synthetic legacy clip", "type", "text"));
        send(legacy, "/app/cliptext", legacyBody, null);
        for (Peer peer : List.of(legacy, modern, target))
            assertThat(json.readTree(receive(peer)).path("payload").asText()).isEqualTo("synthetic legacy clip");
        assertQuiet(foreign);

        byte[] modernBody = json.writeValueAsBytes(Map.of("payload", "synthetic modern clip", "type", "text"));
        send(modern, "/app/cliptext", modernBody, null);
        for (Peer peer : List.of(legacy, target))
            assertThat(json.readTree(receive(peer)).path("payload").asText()).isEqualTo("synthetic modern clip");
        assertQuiet(modern);

        // Discover server IDs using the current authenticated HTTP cookie, never a client-generated STOMP ID.
        String targetId = recipientId(owner, target);
        String legacyId = recipientId(owner, legacy);
        String senderId = recipientId(owner, modern);
        String foreignId = recipientId(outsider, foreign);
        byte[] envelope = binaryFrame(Map.of("type", "text", "encrypted", true,
                "metadata", Map.of("contentSizeBytes", 4, "historyTransferId", "synthetic-binary-transfer")),
                new byte[] { 10, 0, 20, 30 });
        send(modern, "/app/clipbinary", envelope,
                String.join(",", targetId, legacyId, senderId, foreignId));
        assertThat(receive(target)).containsExactly(envelope);
        for (Peer peer : List.of(legacy, modern, foreign)) assertQuiet(peer);

        // Explicit recipient selection also applies to the JSON fallback.
        send(modern, "/app/cliptext", modernBody, legacyId);
        assertThat(json.readTree(receive(legacy)).path("payload").asText()).isEqualTo("synthetic modern clip");
        for (Peer peer : List.of(modern, target, foreign)) assertQuiet(peer);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode history = json.readTree(owner.get("/admin/clipboard-history").body());
            assertThat(history.path("totalElements").asInt()).isEqualTo(4);
            boolean retainedBinary = false;
            for (JsonNode entry : history.path("content")) {
                if ("ccb1".equals(entry.path("payloadFormat").asText())) {
                    retainedBinary = true;
                    assertThat(entry.path("deviceId").asText()).isEqualTo(modern.deviceId);
                }
            }
            assertThat(retainedBinary).as("opaque binary history retains its format and originating device").isTrue();
        });
    }

    @Test
    void httpLogoutClosesOnlyItsLoginSocketsAndRetainsDeviceHandshakeMetadata() throws Exception {
        TestHttpClient firstLogin = TestHttpClient.login(users, port, RoleConstants.ADMIN);
        TestHttpClient secondLogin = anotherLogin(firstLogin);
        Peer first = connect(firstLogin, true);
        Peer sameLogin = connect(firstLogin, false);
        Peer survivor = connect(secondLogin, true);
        for (Peer peer : List.of(first, sameLogin, survivor)) {
            var device = devices.getDevice(peer.deviceId);
            assertThat(device.getDeviceType()).isEqualTo("web");
            assertThat(device.getOsInfo()).isEqualTo("Linux");
            assertThat(device.getIpAddress()).isNotBlank();
            assertThat(devices.isDeviceOnline(peer.deviceId)).isTrue();
        }
        String token = json.readTree(firstLogin.get("/csrf-token").body()).path("token").asText();
        var response = firstLogin.client().send(HttpRequest.newBuilder(firstLogin.url("/logout"))
                .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(first.session.isConnected()).isFalse();
            assertThat(sameLogin.session.isConnected()).isFalse();
            assertThat(devices.isDeviceOnline(first.deviceId)).isFalse();
            assertThat(devices.isDeviceOnline(sameLogin.deviceId)).isFalse();
        });
        assertThat(survivor.session.isConnected()).isTrue();
        assertThat(secondLogin.get("/whoami").body()).contains(firstLogin.username());
        assertThat(devices.isDeviceOnline(survivor.deviceId)).isTrue();
        assertThat(devices.getDevicesForUser(firstLogin.username())).hasSize(3);
        Peer newPeer = connect(secondLogin, false);
        send(survivor, "/app/cliptext", json.writeValueAsBytes(Map.of("payload", "after logout", "type", "text")), null);
        assertThat(json.readTree(receive(newPeer)).path("payload").asText()).isEqualTo("after logout");
        assertQuiet(survivor);
    }

    private Peer connect(TestHttpClient browser, boolean modern) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setDefaultHeartbeat(new long[] { 0, 0 });
        client.setMessageConverter(new SimpleMessageConverter());
        client.start();
        WebSocketHttpHeaders http = new WebSocketHttpHeaders();
        http.set("Cookie", browser.cookies().getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(Collectors.joining("; ")));
        http.set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Firefox/130.0");
        String claimedDeviceId = "synthetic-ws-" + UUID.randomUUID();
        String ownerKey = browser.username().length() + ":" + browser.username() + ":client-id:" + claimedDeviceId;
        String deviceId = "dev-" + UUID.nameUUIDFromBytes(ownerKey.getBytes(StandardCharsets.UTF_8));
        StompHeaders connect = new StompHeaders();
        connect.set("deviceId", claimedDeviceId);
        if (modern) {
            connect.set(P2SDeliveryService.BINARY_HEADER, "ccb1");
            connect.set(P2SDeliveryService.NO_SELF_ECHO_HEADER, "true");
        }
        StompSession session;
        try {
            session = client.connectAsync("ws://localhost:" + port + "/clipsocket", http, connect,
                    new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            client.stop();
            throw failure;
        }
        Peer peer = new Peer(client, session, deviceId, new LinkedBlockingQueue<>());
        peers.add(peer);
        session.subscribe("/user/queue/cliptext", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { peer.messages.add((byte[]) payload); }
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String id = recipientId(browser, peer);
            assertThat(registry.getUser(browser.username()).getSession(id).getSubscriptions()).hasSize(1);
            if (modern) {
                boolean ready = false;
                for (JsonNode entry : json.readTree(browser.get("/clipbinary/recipients").body()).path("recipients"))
                    if (id.equals(entry.path("id").asText())) ready = entry.path("binary").asBoolean();
                assertThat(ready).isTrue();
            }
        });
        return peer;
    }

    private String recipientId(TestHttpClient browser, Peer peer) throws Exception {
        var response = browser.get("/clipbinary/recipients");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        for (JsonNode recipient : json.readTree(response.body()).path("recipients")) {
            String id = recipient.path("id").asText();
            if (peer.deviceId.equals(devices.getDeviceIdForSession(id))) return id;
        }
        throw new AssertionError("Authenticated recipient snapshot does not yet contain the device");
    }

    private void send(Peer peer, String destination, byte[] body, String recipients) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.parseMimeType(destination.endsWith("clipbinary")
                ? "application/octet-stream" : "application/json"));
        if (recipients != null) headers.set(P2SDeliveryService.RECIPIENTS_HEADER, recipients);
        peer.session.send(headers, body);
    }

    private byte[] receive(Peer peer) throws Exception {
        byte[] message = peer.messages.poll(10, TimeUnit.SECONDS);
        assertThat(message).as("clipboard frame reaches subscribed socket").isNotNull();
        return message;
    }

    private void assertQuiet(Peer peer) throws Exception {
        assertThat(peer.messages.poll(350, TimeUnit.MILLISECONDS)).as("no unwanted clipboard echo or recipient leak").isNull();
    }

    private byte[] binaryFrame(Map<String, Object> header, byte[] payload) throws Exception {
        byte[] encoded = json.writeValueAsBytes(header);
        return ByteBuffer.allocate(8 + encoded.length + payload.length).putInt(0x43434231)
                .putInt(encoded.length).put(encoded).put(payload).array();
    }

    private TestHttpClient anotherLogin(TestHttpClient original) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        TestHttpClient browser = new TestHttpClient(original.username(), port,
                HttpClient.newBuilder().cookieHandler(cookies).build(), cookies);
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(browser.get("/login").body());
        assertThat(matcher.find()).isTrue();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA3-512")
                .digest("Synthetic clipboard HTTP test password".getBytes(StandardCharsets.UTF_8)));
        String form = "username=" + original.username() + "&password=" + hash + "&_csrf="
                + java.net.URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
        var response = browser.client().send(HttpRequest.newBuilder(browser.url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(browser.get("/whoami").body()).contains(original.username());
        return browser;
    }

    private record Peer(WebSocketStompClient client, StompSession session, String deviceId,
            BlockingQueue<byte[]> messages) {}
}
