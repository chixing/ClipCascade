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

            // Extract device info from headers / session attributes if available
            String deviceId = getHeaderValue(headerAccessor, "deviceId");
            String deviceType = getHeaderValue(headerAccessor, "deviceType");
            String osInfo = getHeaderValue(headerAccessor, "osInfo");
            String friendlyName = getHeaderValue(headerAccessor, "friendlyName");
            String ipAddress = getHeaderValue(headerAccessor, "ipAddress");
            String userAgent = getHeaderValue(headerAccessor, "userAgent");

            // Infer deviceType and osInfo from User-Agent if not explicitly supplied
            deviceType = inferDeviceType(userAgent, deviceType);
            osInfo = inferOsInfo(userAgent, osInfo);

            // Generate device ID if not provided
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = sessionId;
            }

            // Register device and mark as online
            deviceService.registerDevice(deviceId, username, deviceType, osInfo, ipAddress, friendlyName);
            deviceService.markDeviceOnline(deviceId, sessionId);

            logger.debug("WebSocket connected: user={}, sessionId={}, deviceId={}, type={}, os={}, ip={}",
                    username, sessionId, deviceId, deviceType, osInfo, ipAddress);
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
            // 1. Try native headers on current accessor
            String value = headerAccessor.getFirstNativeHeader(headerName);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }

            // 2. Try CONNECT message frame (where client connect headers are stored in Spring STOMP)
            Object connectMsg = headerAccessor.getHeader(org.springframework.messaging.simp.SimpMessageHeaderAccessor.CONNECT_MESSAGE_HEADER);
            if (connectMsg instanceof org.springframework.messaging.Message<?> msg) {
                StompHeaderAccessor connectAccessor = StompHeaderAccessor.wrap(msg);
                String connectVal = connectAccessor.getFirstNativeHeader(headerName);
                if (connectVal != null && !connectVal.trim().isEmpty()) {
                    return connectVal.trim();
                }
                Map<String, Object> connAttrs = connectAccessor.getSessionAttributes();
                if (connAttrs != null && connAttrs.containsKey(headerName)) {
                    Object val = connAttrs.get(headerName);
                    if (val != null) {
                        return val.toString().trim();
                    }
                }
            }

            // 3. Try session attributes (populated by HandshakeInterceptor)
            Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
            if (sessionAttributes != null && sessionAttributes.containsKey(headerName)) {
                Object val = sessionAttributes.get(headerName);
                if (val != null) {
                    return val.toString().trim();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    public static String inferDeviceType(String userAgent, String explicitType) {
        if (explicitType != null && !explicitType.trim().isEmpty()) {
            return explicitType.trim();
        }
        if (userAgent == null) {
            return "desktop";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("android") || ua.contains("iphone") || ua.contains("ipad") || ua.contains("mobile") || ua.contains("okhttp")) {
            return "mobile";
        }
        if (ua.contains("python") || ua.contains("clipcascade-desktop")) {
            return "desktop";
        }
        if (ua.contains("mozilla") || ua.contains("chrome") || ua.contains("safari") || ua.contains("firefox") || ua.contains("edge")) {
            return "web";
        }
        return "desktop";
    }

    public static String inferOsInfo(String userAgent, String explicitOs) {
        if (explicitOs != null && !explicitOs.trim().isEmpty()) {
            return explicitOs.trim();
        }
        if (userAgent == null) {
            return "Unknown OS";
        }
        String uaLower = userAgent.toLowerCase();
        if (uaLower.contains("windows nt 10.0")) return "Windows 10/11";
        if (uaLower.contains("windows nt 6.3")) return "Windows 8.1";
        if (uaLower.contains("windows nt 6.1")) return "Windows 7";
        if (uaLower.contains("windows")) return "Windows";
        if (uaLower.contains("android")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Android\\s+([0-9.]+)").matcher(userAgent);
            if (m.find()) return "Android " + m.group(1);
            return "Android";
        }
        if (uaLower.contains("iphone") || uaLower.contains("ipad") || uaLower.contains("ios")) return "iOS";
        if (uaLower.contains("macintosh") || uaLower.contains("mac os x")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Mac OS X\\s+([0-9_.]+)").matcher(userAgent);
            if (m.find()) return "macOS " + m.group(1).replace('_', '.');
            return "macOS";
        }
        if (uaLower.contains("ubuntu")) return "Ubuntu Linux";
        if (uaLower.contains("linux")) return "Linux";
        if (uaLower.contains("python")) return "Python Desktop App";
        return "Unknown OS";
    }
}
