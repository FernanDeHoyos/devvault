package com.devvault.runtime.infrastructure;

import com.devvault.runtime.api.LogWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LogWebSocketHandler logWebSocketHandler;

    public WebSocketConfig(LogWebSocketHandler logWebSocketHandler) {
        this.logWebSocketHandler = logWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // El patrón coincide con /api/v1/projects/{cualquier-uuid}/logs
        registry.addHandler(logWebSocketHandler, "/api/v1/projects/*/logs")
                .setAllowedOrigins("http://localhost:5050", "http://127.0.0.1:5050");
    }
}
