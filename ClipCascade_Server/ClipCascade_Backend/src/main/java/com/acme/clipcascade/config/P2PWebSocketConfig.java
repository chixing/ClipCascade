package com.acme.clipcascade.config;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.acme.clipcascade.utils.IpAddressResolver;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "app.p2p", name = "enabled", havingValue = "true", matchIfMissing = false)
public class P2PWebSocketConfig implements WebSocketConfigurer {

    private final P2PWebSocketHandler p2pWebSocketHandler;
    private final ClipCascadeProperties clipCascadeProperties;

    public P2PWebSocketConfig(
            P2PWebSocketHandler p2pWebSocketHandler,
            ClipCascadeProperties clipCascadeProperties) {

        this.p2pWebSocketHandler = p2pWebSocketHandler;
        this.clipCascadeProperties = clipCascadeProperties;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(p2pWebSocketHandler, "/p2psignaling")
                .setAllowedOrigins(clipCascadeProperties.getAllowedOrigins())
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(
                            @NonNull ServerHttpRequest request,
                            @NonNull ServerHttpResponse response,
                            @NonNull WebSocketHandler wsHandler,
                            @NonNull Map<String, Object> attributes) {
                        String ip = null;
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            ip = IpAddressResolver.getIpAddressFromRequest(servletRequest.getServletRequest());
                        }
                        if (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip)) {
                            ip = IpAddressResolver.getIpAddressFromHeaders(request.getHeaders(), request.getRemoteAddress());
                        }
                        attributes.put("ipAddress", ip);

                        String userAgent = request.getHeaders().getFirst("User-Agent");
                        if (userAgent != null && !userAgent.isEmpty()) {
                            attributes.put("userAgent", userAgent);
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(
                            @NonNull ServerHttpRequest request,
                            @NonNull ServerHttpResponse response,
                            @NonNull WebSocketHandler wsHandler,
                            Exception exception) {
                    }
                });
    }
}
