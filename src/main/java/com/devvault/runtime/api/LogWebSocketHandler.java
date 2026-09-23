package com.devvault.runtime.api;

import com.devvault.runtime.application.LocalProcessLogHub;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.ContainerKind;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.DockerClientAdapter;
import com.devvault.runtime.infrastructure.ServiceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementa WS /projects/{id}/logs?service={name} (CU-07). Soporta dos
 * fuentes de log: contenedores Docker (via docker-java) y procesos locales
 * (via LocalProcessLogHub), según el `kind` del Container asociado.
 */
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);
    private static final Pattern PROJECT_ID_PATTERN =
            Pattern.compile("/api/v1/projects/([0-9a-fA-F-]{36})/logs");

    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;
    private final DockerClientAdapter dockerClientAdapter;
    private final LocalProcessLogHub localProcessLogHub;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, ResultCallback<Frame>> activeDockerStreams = new ConcurrentHashMap<>();
    private final Map<String, LocalSubscription> activeLocalStreams = new ConcurrentHashMap<>();

    private record LocalSubscription(UUID serviceId, Consumer<String> consumer) {
    }

    public LogWebSocketHandler(ServiceRepository serviceRepository,
                                ContainerRepository containerRepository,
                                DockerClientAdapter dockerClientAdapter,
                                LocalProcessLogHub localProcessLogHub) {
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.dockerClientAdapter = dockerClientAdapter;
        this.localProcessLogHub = localProcessLogHub;
    }

    /**
     * Maneja la conexión WebSocket.
     * @param session Sesión WebSocket
     * @throws IOException Si hay un error al cerrar la sesión
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        // Parsear la URI para obtener el ID del proyecto y el nombre del servicio
        UriComponents uri = UriComponentsBuilder.fromUri(session.getUri()).build();
        Matcher matcher = PROJECT_ID_PATTERN.matcher(uri.getPath());

        // Validar que la ruta sea válida
        if (!matcher.find()) {
            session.close(CloseStatus.BAD_DATA.withReason("Ruta inválida"));
            return;
        }

        // Obtener el ID del proyecto y el nombre del servicio de la URI
        UUID projectId = UUID.fromString(matcher.group(1));
        String serviceName = uri.getQueryParams().getFirst("service");

        // Validar que el nombre del servicio sea válido
        if (serviceName == null || serviceName.isBlank()) {
            session.close(CloseStatus.BAD_DATA.withReason("Falta el parámetro 'service'"));
            return;
        }

        // Obtener el servicio
        Optional<Service> service = serviceRepository.findByProjectIdAndName(projectId, serviceName);

        // Validar que el servicio exista
        if (service.isEmpty()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Servicio no encontrado"));
            return;
        }

        // Obtener el contenedor
        Optional<Container> container = containerRepository.findByServiceId(service.get().getId());

        // Validar que el contenedor exista
        if (container.isEmpty()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("No hay contenedor/proceso activo para ese servicio"));
            return;
        }

        // Iniciar el stream según el tipo de contenedor
        if (container.get().getKind() == ContainerKind.LOCAL_PROCESS) {
            startLocalProcessStream(session, service.get().getId());
        } else {
            startDockerStream(session, container.get());
        }
    }

    /**
     * Inicia el stream de logs de Docker.
     * @param session Sesión WebSocket
     * @param container Contenedor Docker
     */
    private void startDockerStream(WebSocketSession session, Container container) {
        // Validar que el contenedor Docker tenga un ID registrado
        if (container.getDockerContainerId() == null) {
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Contenedor Docker sin id registrado"));
            } catch (IOException ignored) {
            }
            return;
        }

        // Iniciar el stream de logs de Docker
        log.info(">>> Streaming de logs Docker iniciado: session={}", session.getId());
        ResultCallback<Frame> callback = dockerClientAdapter.streamLogs(
                container.getDockerContainerId(),
                frame -> sendLine(session, new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing())
        );
        activeDockerStreams.put(session.getId(), callback);
    }

    /**
     * Inicia el stream de logs de proceso local.
     * @param session Sesión WebSocket
     * @param serviceId ID del servicio
     */
    private void startLocalProcessStream(WebSocketSession session, UUID serviceId) {
        log.info(">>> Streaming de logs de proceso local iniciado: session={}", session.getId());

        // 1. Historial reciente, para no perder lo que pasó antes de conectar
        localProcessLogHub.recentLines(serviceId).forEach(line -> sendLine(session, line));

        // 2. Suscripción a líneas nuevas en vivo
        Consumer<String> consumer = line -> sendLine(session, line);
        localProcessLogHub.subscribe(serviceId, consumer);
        activeLocalStreams.put(session.getId(), new LocalSubscription(serviceId, consumer));
    }

    /**
     * Maneja el cierre de la conexión WebSocket.
     * @param session Sesión WebSocket
     * @param status Estado de cierre
     * @throws IOException Si hay un error al cerrar la sesión
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws IOException {

        // Eliminar el stream de logs de Docker
        ResultCallback<Frame> dockerCallback = activeDockerStreams.remove(session.getId());
        if (dockerCallback != null) {
            dockerCallback.close();
        }

        // Eliminar el stream de logs de proceso local
        LocalSubscription localSubscription = activeLocalStreams.remove(session.getId());
        if (localSubscription != null) {
            localProcessLogHub.unsubscribe(localSubscription.serviceId(), localSubscription.consumer());
        }

        log.info(">>> Streaming de logs detenido: session={}", session.getId());
    }

    /**
     * Envía una línea de log a la sesión WebSocket.
     * @param session Sesión WebSocket
     * @param message Mensaje de log
     */
    private void sendLine(WebSocketSession session, String message) {
        // Validar que la sesión esté abierta
        if (!session.isOpen()) {
            return;
        }

        // Crear el payload del mensaje de log
        try {
            Map<String, Object> payload = Map.of(
                    "timestamp", Instant.now().toString(),
                    "level", "INFO",
                    "message", message
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug(">>> No se pudo enviar línea de log (sesión probablemente cerrada)", e);
        }
    }
}