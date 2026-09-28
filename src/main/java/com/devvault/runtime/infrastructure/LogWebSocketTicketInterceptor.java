package com.devvault.runtime.infrastructure;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.devvault.auth.WebSocketTicketService;

@Component
public class LogWebSocketTicketInterceptor implements HandshakeInterceptor {
    private static final Pattern PROJECT_ID = Pattern.compile(
            "/api/v1/projects/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/logs");
    private final WebSocketTicketService ticketService;

    public LogWebSocketTicketInterceptor(WebSocketTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        UriComponents uri = UriComponentsBuilder.fromUri(request.getURI()).build();
        Matcher projectMatcher = PROJECT_ID.matcher(uri.getPath());
        String ticket = uri.getQueryParams().getFirst("ticket");
        String serviceName = uri.getQueryParams().getFirst("service");
        boolean valid = projectMatcher.matches() && ticket != null && serviceName != null
                && ticketService.consume(ticket, UUID.fromString(projectMatcher.group(1)), serviceName);
        if (!valid) response.setStatusCode(HttpStatus.FORBIDDEN);
        return valid;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Exception exception) {
        // No post-handshake action is required.
    }
}
