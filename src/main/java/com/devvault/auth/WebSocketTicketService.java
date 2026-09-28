package com.devvault.auth;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

@Service
public class WebSocketTicketService {
    private static final long TICKET_TTL_SECONDS = 30;
    private final ConcurrentMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    public IssuedTicket issue(UUID projectId, String serviceName) {
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        String value = UUID.randomUUID().toString();
        tickets.put(value, new Ticket(projectId, serviceName, expiresAt));
        if (tickets.size() > 1000) {
            Instant now = Instant.now();
            tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
        return new IssuedTicket(value, expiresAt);
    }

    public boolean consume(String value, UUID projectId, String serviceName) {
        if (value == null) return false;
        Ticket ticket = tickets.remove(value);
        return ticket != null && ticket.expiresAt().isAfter(Instant.now())
                && ticket.projectId().equals(projectId) && ticket.serviceName().equals(serviceName);
    }

    private record Ticket(UUID projectId, String serviceName, Instant expiresAt) {}
    public record IssuedTicket(String value, Instant expiresAt) {}
}
