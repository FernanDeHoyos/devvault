package com.devvault.runtime.api;

import com.devvault.runtime.domain.Container;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementa WS /projects/{id}/logs?service={name} (CU-07). Se resuelve
 * manualmente la ruta y el query param porque el soporte de @PathVariable
 * de Spring MVC no aplica a WebSocketHandler de bajo nivel.
 */
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);
    private static final Pattern PROJECT_ID_PATTERN =
            Pattern.compile("/api/v1/projects/([0-9a-fA-F-]{36})/logs");

    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;
    private final DockerClientAdapter dockerClientAdapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, ResultCallback<Frame>> activeStreams = new ConcurrentHashMap<>();

    public LogWebSocketHandler(ServiceRepository serviceRepository,
                                ContainerRepository containerRepository,
                                DockerClientAdapter dockerClientAdapter) {
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.dockerClientAdapter = dockerClientAdapter;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        UriComponents uri = UriComponentsBuilder.fromUri(session.getUri()).build();
        Matcher matcher = PROJECT_ID_PATTERN.matcher(uri.getPath());

        if (!matcher.find()) {
            session.close(CloseStatus.BAD_DATA.withReason("Ruta inválida"));
            return;
        }

        UUID projectId = UUID.fromString(matcher.group(1));
        String serviceName = uri.getQueryParams().getFirst("service");

        if (serviceName == null || serviceName.isBlank()) {
            session.close(CloseStatus.BAD_DATA.withReason("Falta el parámetro 'service'"));
            return;
        }

        Optional<Container> container = findContainer(projectId, serviceName);
        if (container.isEmpty() || container.get().getDockerContainerId() == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("No hay contenedor activo para ese servicio"));
            return;
        }

        log.info(">>> Streaming de logs iniciado: project={}, service={}", projectId, serviceName);

        ResultCallback<Frame> callback = dockerClientAdapter.streamLogs(
                container.get().getDockerContainerId(),
                frame -> sendLine(session, frame)
        );
        activeStreams.put(session.getId(), callback);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws IOException {
        ResultCallback<Frame> callback = activeStreams.remove(session.getId());
        if (callback != null) {
            callback.close();
            log.info(">>> Streaming de logs detenido: session={}", session.getId());
        }
    }

    private Optional<Container> findContainer(UUID projectId, String serviceName) {
        Optional<Service> service = serviceRepository.findByProjectIdAndName(projectId, serviceName);
        if (service.isEmpty()) {
            return Optional.empty();
        }
        return containerRepository.findByServiceId(service.get().getId());
    }

    private void sendLine(WebSocketSession session, Frame frame) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "timestamp", Instant.now().toString(),
                    "level", "INFO",
                    "message", new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug(">>> No se pudo enviar línea de log (sesión probablemente cerrada)", e);
        }
    }
}