package com.acme.clipcascade.config;

import java.security.Principal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.acme.clipcascade.model.UserPrincipal;
import com.acme.clipcascade.service.DeviceService;

@Component
@ConditionalOnProperty(prefix = "app.p2p", name = "enabled", havingValue = "false", matchIfMissing = true)
public class WebSocketEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final DeviceService deviceService;

    public WebSocketEventListener(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        Principal principal = headerAccessor.getUser();

        if (principal == null) {
            return;
        }

        try {
            UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal)
                    .getPrincipal();
            String username = userPrincipal.getUsername();

            // Extract device info from headers if available
            String deviceId = getHeaderValue(headerAccessor, "deviceId");
            String deviceType = getHeaderValue(headerAccessor, "deviceType");
            String osInfo = getHeaderValue(headerAccessor, "osInfo");

            // Generate device ID if not provided
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = sessionId;
            }

            // Register device and mark as online
            deviceService.registerDevice(deviceId, username, deviceType, osInfo);
            deviceService.markDeviceOnline(deviceId, sessionId);

            logger.debug("WebSocket connected: user={}, sessionId={}, deviceId={}", username, sessionId, deviceId);
        } catch (Exception e) {
            logger.debug("Failed to process WebSocket connect event: {}", e.getMessage());
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        try {
            deviceService.markDeviceOffline(sessionId);
            logger.debug("WebSocket disconnected: sessionId={}", sessionId);
        } catch (Exception e) {
            logger.debug("Failed to process WebSocket disconnect event: {}", e.getMessage());
        }
    }

    private String getHeaderValue(StompHeaderAccessor headerAccessor, String headerName) {
        try {
            // Try native headers first
            Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
            if (sessionAttributes != null && sessionAttributes.containsKey(headerName)) {
                return (String) sessionAttributes.get(headerName);
            }

            // Try STOMP headers
            String value = headerAccessor.getFirstNativeHeader(headerName);
            if (value != null) {
                return value;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
