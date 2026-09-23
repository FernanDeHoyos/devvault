package com.devvault.runtime.application;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Para procesos Docker, docker-java nos deja "re-conectarnos" a los logs de
 * un contenedor en cualquier momento (docker logs -f funciona así). Un
 * proceso local (npm run dev) no tiene ese lujo: su salida solo se puede
 * leer una vez, en tiempo real, mientras el proceso vive. Este componente
 * guarda un pequeño buffer de las últimas líneas por servicio y retransmite
 * las nuevas a quien se conecte por WebSocket — así el navegador no se pierde
 * completamente el historial reciente si conecta unos segundos tarde.
 */
@Component
public class LocalProcessLogHub {

    private static final int BUFFER_SIZE = 200;

    private final Map<UUID, Deque<String>> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    /**
     * CU-05 Publica una línea de log.
     * @param serviceId ID del servicio
     * @param line Línea de log
     */
    public void publish(UUID serviceId, String line) {
        Deque<String> buffer = buffers.computeIfAbsent(serviceId, id -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(line);
            if (buffer.size() > BUFFER_SIZE) {
                buffer.removeFirst();
            }
        }
        subscribers.getOrDefault(serviceId, Set.of()).forEach(consumer -> consumer.accept(line));
    }

    /**
     * CU-06 Obtiene las líneas de log recientes.
     * @param serviceId ID del servicio
     * @return Lista de líneas de log recientes
     */
    public List<String> recentLines(UUID serviceId) {
        Deque<String> buffer = buffers.get(serviceId);
        if (buffer == null) return List.of();
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    /**
     * Se subscribe a las líneas de log.
     * @param serviceId ID del servicio
     * @param consumer Consumidor de líneas de log
     */
    public void subscribe(UUID serviceId, Consumer<String> consumer) {
        subscribers.computeIfAbsent(serviceId, id -> new CopyOnWriteArraySet<>()).add(consumer);
    }

    /**
     * Se desuscribe de las líneas de log.
     * @param serviceId ID del servicio
     * @param consumer Consumidor de líneas de log
     */
    public void unsubscribe(UUID serviceId, Consumer<String> consumer) {
        subscribers.getOrDefault(serviceId, Set.of()).remove(consumer);
    }
}