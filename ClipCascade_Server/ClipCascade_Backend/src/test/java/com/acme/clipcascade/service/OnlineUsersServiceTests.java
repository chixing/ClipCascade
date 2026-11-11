package com.acme.clipcascade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.acme.clipcascade.config.ClipCascadeProperties;
import com.acme.clipcascade.config.P2PWebSocketHandler;

class OnlineUsersServiceTests {
    @Test
    void countsConnectionsPerUserAndRemovesOnlyTheLastConnection() {
        var service = p2sService();
        connect(service, "alice-1", "alice");
        connect(service, "alice-2", "alice");
        connect(service, "bob-1", "bob");

        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 2, "bob", 1));
        disconnect(service, "alice-1");
        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 1, "bob", 1));
        disconnect(service, "alice-2");
        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("bob", 1));
        disconnect(service, "bob-1");
        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void repeatedAndUnknownDisconnectsDoNotDecrementAnotherSession() {
        var service = p2sService();
        connect(service, "old", "alice");
        connect(service, "current", "alice");

        disconnect(service, "old");
        disconnect(service, "old");
        disconnect(service, "unknown");

        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 1));
    }

    @Test
    void ignoresConnectionsWithoutAnAuthenticatedUserOrSessionId() {
        var service = p2sService();
        service.onSessionConnected(new SessionConnectedEvent(this, message("anonymous", null)));
        service.onSessionConnected(new SessionConnectedEvent(this, message(null, () -> "alice")));

        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void p2pModeStillDelegatesPresenceAndIgnoresStompEvents() {
        var properties = mock(ClipCascadeProperties.class);
        when(properties.isP2pEnabled()).thenReturn(true);
        var handler = mock(P2PWebSocketHandler.class);
        when(handler.getOnlineUsers()).thenReturn(Map.of("peer", 2));
        var service = new OnlineUsersService(properties, handler);

        connect(service, "stomp", "alice");
        disconnect(service, "stomp");

        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("peer", 2));
    }

    @Test
    void p2pModeWithoutAHandlerHasNoOnlineUsers() {
        var properties = mock(ClipCascadeProperties.class);
        when(properties.isP2pEnabled()).thenReturn(true);

        assertThat(new OnlineUsersService(properties, null).getOnlineUsers()).isEmpty();
    }

    @Test
    void lastDisconnectCannotEraseAnOverlappingReconnect() throws Exception {
        var service = p2sService();
        var counts = new PausingCounts();
        ReflectionTestUtils.setField(service, "p2sConnectionCounts", counts);
        connect(service, "old", "alice");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var disconnect = executor.submit(() -> disconnect(service, "old"));
            var reconnect = executor.submit(() -> {
                await(counts.removalStarted);
                try {
                    connect(service, "new", "alice");
                } finally {
                    counts.reconnectCompleted.countDown();
                }
            });
            disconnect.get(10, TimeUnit.SECONDS);
            reconnect.get(10, TimeUnit.SECONDS);
        }

        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 1));
        disconnect(service, "new");
        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void reconnectCannotIncrementACounterRemovedByTheLastDisconnect() throws Exception {
        var service = p2sService();
        var counts = new PausingIncrementCounts();
        ReflectionTestUtils.setField(service, "p2sConnectionCounts", counts);
        connect(service, "old", "alice");
        counts.armed = true;

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reconnect = executor.submit(() -> connect(service, "new", "alice"));
            var disconnect = executor.submit(() -> {
                await(counts.incrementStarted);
                try {
                    disconnect(service, "old");
                } finally {
                    counts.disconnectCompleted.countDown();
                }
            });
            reconnect.get(10, TimeUnit.SECONDS);
            disconnect.get(10, TimeUnit.SECONDS);
        }

        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 1));
        disconnect(service, "new");
        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void duplicateConnectEventsDoNotLeakPresenceOrChangeSessionOwner() {
        var service = p2sService();
        connect(service, "same", "alice");
        connect(service, "same", "alice");
        connect(service, "same", "bob");
        assertThat(service.getOnlineUsers()).isEqualTo(Map.of("alice", 1));
        disconnect(service, "same");
        assertThat(service.getOnlineUsers()).isEmpty();
    }

    @Test
    void disconnectDuringConnectCannotLeaveAnOrphanedCount() throws Exception {
        var service = p2sService();
        var incrementStarted = new CountDownLatch(1);
        var disconnectAttempted = new CountDownLatch(1);
        var counts = new ConcurrentHashMap<String, AtomicInteger>() {
            @Override
            public AtomicInteger compute(String key,
                    BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger> update) {
                incrementStarted.countDown();
                await(disconnectAttempted);
                return super.compute(key, update);
            }
        };
        var sessions = new ConcurrentHashMap<String, String>() {
            @Override
            public String computeIfPresent(String key,
                    BiFunction<? super String, ? super String, ? extends String> update) {
                disconnectAttempted.countDown();
                return super.computeIfPresent(key, update);
            }
        };
        ReflectionTestUtils.setField(service, "p2sConnectionCounts", counts);
        ReflectionTestUtils.setField(service, "p2sSessionToUser", sessions);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var connect = executor.submit(() -> connect(service, "same", "alice"));
            await(incrementStarted);
            var disconnect = executor.submit(() -> disconnect(service, "same"));
            connect.get(10, TimeUnit.SECONDS);
            disconnect.get(10, TimeUnit.SECONDS);
        }
        assertThat(service.getOnlineUsers()).isEmpty();
        assertThat(sessions).isEmpty();
    }

    private static OnlineUsersService p2sService() {
        return new OnlineUsersService(mock(ClipCascadeProperties.class), null);
    }

    private static void connect(OnlineUsersService service, String sessionId, String username) {
        service.onSessionConnected(new SessionConnectedEvent(service, message(sessionId, () -> username)));
    }

    private static void disconnect(OnlineUsersService service, String sessionId) {
        service.onSessionDisconnect(new SessionDisconnectEvent(
                service, message(sessionId, null), sessionId, CloseStatus.NORMAL));
    }

    private static Message<byte[]> message(String sessionId, Principal principal) {
        var headers = StompHeaderAccessor.create(StompCommand.CONNECTED);
        headers.setSessionId(sessionId);
        headers.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the other session event");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /**
     * Forces a reconnect to overlap the last disconnect, without relying on sleeps.
     * The legacy remove path waits for the completed reconnect and then loses it.
     * The atomic path holds the map's key lock until the reconnect has attempted
     * its update, so that update must run after the removal.
     */
    private static class PausingCounts extends ConcurrentHashMap<String, AtomicInteger> {
        final CountDownLatch removalStarted = new CountDownLatch(1);
        final CountDownLatch reconnectAttempted = new CountDownLatch(1);
        final CountDownLatch reconnectCompleted = new CountDownLatch(1);

        @Override
        public AtomicInteger remove(Object key) {
            removalStarted.countDown();
            await(reconnectCompleted);
            return super.remove(key);
        }

        @Override
        public AtomicInteger computeIfPresent(String key,
                BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger> update) {
            return super.computeIfPresent(key, (username, count) -> {
                AtomicInteger result = update.apply(username, count);
                if (result == null && removalStarted.getCount() > 0) {
                    removalStarted.countDown();
                    await(reconnectAttempted);
                }
                return result;
            });
        }

        @Override
        public AtomicInteger compute(String key,
                BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger> update) {
            markReconnectAttempt();
            return super.compute(key, update);
        }

        @Override
        public AtomicInteger computeIfAbsent(String key,
                Function<? super String, ? extends AtomicInteger> create) {
            markReconnectAttempt();
            return super.computeIfAbsent(key, create);
        }

        private void markReconnectAttempt() {
            if (removalStarted.getCount() == 0) {
                reconnectAttempted.countDown();
            }
        }
    }

    /** Covers the opposite ordering: reconnect obtains the counter first. */
    private static class PausingIncrementCounts extends ConcurrentHashMap<String, AtomicInteger> {
        volatile boolean armed;
        final CountDownLatch incrementStarted = new CountDownLatch(1);
        final CountDownLatch disconnectAttempted = new CountDownLatch(1);
        final CountDownLatch disconnectCompleted = new CountDownLatch(1);

        @Override
        public AtomicInteger computeIfAbsent(String key,
                Function<? super String, ? extends AtomicInteger> create) {
            AtomicInteger count = super.computeIfAbsent(key, create);
            if (armed) {
                incrementStarted.countDown();
                await(disconnectCompleted);
            }
            return count;
        }

        @Override
        public AtomicInteger compute(String key,
                BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger> update) {
            return super.compute(key, (username, count) -> {
                AtomicInteger result = update.apply(username, count);
                if (armed) {
                    incrementStarted.countDown();
                    await(disconnectAttempted);
                }
                return result;
            });
        }

        @Override
        public AtomicInteger get(Object key) {
            disconnectAttempted.countDown();
            return super.get(key);
        }

        @Override
        public AtomicInteger computeIfPresent(String key,
                BiFunction<? super String, ? super AtomicInteger, ? extends AtomicInteger> update) {
            disconnectAttempted.countDown();
            return super.computeIfPresent(key, update);
        }
    }
}
