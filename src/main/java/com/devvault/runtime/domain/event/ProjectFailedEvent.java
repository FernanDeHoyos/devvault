package com.devvault.runtime.domain.event;

import java.util.UUID;
 
/**
 * Publicado cuando un RuntimeInstance termina en FAILED (CU-05, CU-12).
 * El módulo automation lo escucha sin que runtime sepa que existe —
 * misma regla de comunicación que WorkspaceScanRequestedEvent.
 */
public record ProjectFailedEvent(UUID projectId, String reason) {
}
 
