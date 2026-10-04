package com.acme.clipcascade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import com.acme.clipcascade.config.ClipCascadeProperties;
import com.acme.clipcascade.config.P2PWebSocketHandler;
import com.acme.clipcascade.config.WebSocketEventListener;
import com.acme.clipcascade.controller.ClipCascadeController;
import com.acme.clipcascade.model.ClipboardData;
import com.acme.clipcascade.model.Device;
import com.acme.clipcascade.model.UserPrincipal;
import com.acme.clipcascade.model.Users;
import com.acme.clipcascade.repo.DeviceRepo;
import com.acme.clipcascade.service.ClipboardHistoryService;
import com.acme.clipcascade.service.DeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;

class DeviceConnectionTests {
    @Test
    void historyAndPresenceUseTheRegisteredIdInsteadOfTheClaimedId() {
        DeviceService devices = mock(DeviceService.class);
        ClipboardHistoryService history = mock(ClipboardHistoryService.class);
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        when(devices.registerDevice(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Device("owner-scoped-id", "owner"));
        var controller = new ClipCascadeController(mock(ClipCascadeProperties.class), null, null,
                messages, null, null, null, null, null, null, devices, history);
        var headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("socket");
        headers.setSessionAttributes(Map.of());

        controller.sendPrivateMessage(authentication(),
                new ClipboardData("synthetic clipboard", "text", Map.of("deviceId", "claimed-id")), headers);

        verify(devices).refreshDeviceForSession("owner-scoped-id", "socket");
        verify(history).recordClipboard(eq("owner"), eq("owner-scoped-id"), any(ClipboardData.class));
    }

    @Test
    void connectedEventReusesTheRegistrationFromTheConnectEvent() {
        DeviceService devices = mock(DeviceService.class);
        when(devices.registerDevice(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Device("generated-fallback-id", "owner"));
        var listener = new WebSocketEventListener(devices);
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId("socket");
        headers.setSessionAttributes(Map.of());
        headers.setUser(authentication());
        Message<byte[]> connect = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());

        listener.handleWebSocketConnectListener(new SessionConnectEvent(this, connect));
        when(devices.getDeviceIdForSession("socket")).thenReturn("generated-fallback-id");
        listener.handleWebSocketConnectedListener(new SessionConnectedEvent(this, connect));

        verify(devices, times(1)).registerDevice(isNull(), eq("owner"), eq("desktop"), eq("Unknown OS"), isNull(), isNull());
        verify(devices, times(1)).markDeviceOnline("generated-fallback-id", "socket");
        verify(devices).refreshDeviceForSession("generated-fallback-id", "socket");
    }

    @Test
    void aQueuedMessageAndConnectedEventAfterDisconnectDoNotRestorePresence() {
        var repository = mock(DeviceRepo.class);
        Map<String, Device> stored = new HashMap<>();
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.saveAndFlush(any(Device.class))).thenAnswer(call -> {
            Device device = call.getArgument(0);
            stored.put(device.getId(), device);
            return device;
        });
        var devices = new DeviceService(repository);
        var listener = new WebSocketEventListener(devices);
        var stomp = StompHeaderAccessor.create(StompCommand.CONNECT);
        stomp.setSessionId("closed-socket");
        stomp.setSessionAttributes(Map.of("ipAddress", "192.0.2.10"));
        stomp.setUser(authentication());
        stomp.setNativeHeader("deviceId", "browser-uuid");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], stomp.getMessageHeaders());
        listener.handleWebSocketConnectListener(new SessionConnectEvent(this, message));
        String registeredId = devices.getDeviceIdForSession("closed-socket");
        devices.markDeviceOffline("closed-socket");

        listener.handleWebSocketConnectedListener(new SessionConnectedEvent(this, message));
        var history = mock(ClipboardHistoryService.class);
        var controller = new ClipCascadeController(mock(ClipCascadeProperties.class), null, null,
                mock(SimpMessagingTemplate.class), null, null, null, null, null, null, devices, history);
        controller.sendPrivateMessage(authentication(),
                new ClipboardData("queued synthetic clip", "text", Map.of("deviceId", "browser-uuid")), stomp);

        assertThat(devices.getDeviceIdForSession("closed-socket")).isNull();
        assertThat(devices.isDeviceOnline(registeredId)).isFalse();
        assertThat(devices.getOnlineDeviceCount()).isZero();
        verify(history).recordClipboard(eq("owner"), eq(registeredId), any(ClipboardData.class));
    }

    @Test
    void oversizedClientMetadataDoesNotSuppressClipboardHistory() {
        var repository = mock(DeviceRepo.class);
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Device.class))).thenAnswer(call -> call.getArgument(0));
        var devices = new DeviceService(repository);
        var history = mock(ClipboardHistoryService.class);
        var controller = new ClipCascadeController(mock(ClipCascadeProperties.class), null, null,
                mock(SimpMessagingTemplate.class), null, null, null, null, null, null, devices, history);
        var headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId("unknown-socket");
        headers.setSessionAttributes(Map.of());

        controller.sendPrivateMessage(authentication(), new ClipboardData("synthetic clipboard", "text",
                Map.of("deviceId", "x".repeat(1000), "deviceType", "t".repeat(100), "osInfo", "o".repeat(200),
                        "friendlyName", "n".repeat(200), "ipAddress", "i".repeat(200))), headers);

        verify(history).recordClipboard(eq("owner"), any(String.class), any(ClipboardData.class));
        assertThat(devices.getOnlineDeviceCount()).isZero();
    }

    @Test
    void p2pPresenceUsesTheRegisteredFallbackNotItsRandomSignalingPeerId() throws Exception {
        DeviceService devices = mock(DeviceService.class);
        when(devices.registerDevice(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Device("legacy-stock-device", "owner"));
        var properties = mock(ClipCascadeProperties.class);
        when(properties.getMaxWsGlobalConnections()).thenReturn(-1L);
        when(properties.getMaxWsConnectionsPerUser()).thenReturn(-1L);
        var handler = new P2PWebSocketHandler(new ObjectMapper(), properties, devices);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket");
        when(session.getPrincipal()).thenReturn(() -> "owner");
        when(session.getAttributes()).thenReturn(Map.of("ipAddress", "192.0.2.10"));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(devices).registerDevice(isNull(), eq("owner"), eq("p2p"), eq("Unknown OS"), eq("192.0.2.10"), isNull());
        verify(devices).markDeviceOnline("legacy-stock-device", "socket");
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var principal = new UserPrincipal(new Users("owner", "unused", "USER"), null);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
