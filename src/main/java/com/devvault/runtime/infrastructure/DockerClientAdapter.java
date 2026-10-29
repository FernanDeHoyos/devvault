package com.devvault.runtime.infrastructure;

import com.devvault.runtime.application.StartProjectUseCase;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Envuelve el cliente de Docker Engine validado en el spike
 * (DockerConnectionSpike)
 * como un bean singleton reutilizable por todo el módulo runtime.
 */
@Component
public class DockerClientAdapter {

    private static final Logger log = LoggerFactory.getLogger(StartProjectUseCase.class);

    private final DockerClient dockerClient;
    private final DockerHttpClient httpClient;

    public DockerClientAdapter() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

        this.httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * Lista los contenedores creados por un stack de Docker Compose específico,
     * vía su label.
     * 
     * @param composeProjectName Nombre del proyecto de Docker Compose
     * @return Lista de contenedores
     */
    public List<Container> listContainersByComposeProject(String composeProjectName) {
        return dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("com.docker.compose.project", composeProjectName))
                .exec();
    }

    /** Verifica si el contenedor está corriendo (usado también por Monitoring). */
    public boolean isRunning(String dockerContainerId) {
        var state = dockerClient.inspectContainerCmd(dockerContainerId).exec().getState();
        return Boolean.TRUE.equals(state.getRunning());
    }

    /** Devuelve el estado de disponibilidad, incluyendo el healthcheck configurado. */
    public String readinessStatus(String dockerContainerId) {
        var state = dockerClient.inspectContainerCmd(dockerContainerId).exec().getState();
        if (!Boolean.TRUE.equals(state.getRunning())) {
            return state.getStatus() == null ? "stopped" : state.getStatus().toLowerCase(java.util.Locale.ROOT);
        }
        var health = state.getHealth();
        if (health == null || health.getStatus() == null || health.getStatus().isBlank()) {
            return "running";
        }
        return health.getStatus().toLowerCase(java.util.Locale.ROOT);
    }

    /** Obtiene una muestra puntual, equivalente a `docker stats --no-stream`. */
    public Statistics readStats(String dockerContainerId) {
        AtomicReference<Statistics> sample = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics statistics) {
                sample.set(statistics);
            }

            @Override
            public void onError(Throwable throwable) {
                received.countDown();
                log.warn("No se pudieron obtener métricas para el contenedor {}: {}: {}",
                        dockerContainerId, throwable.getClass().getSimpleName(), throwable.getMessage());
            }

            @Override
            public void onComplete() {
                while (received.getCount() > 0) received.countDown();
            }
        };
        dockerClient.statsCmd(dockerContainerId)
                .withNoStream(true)
                .exec(callback);
        try {
            received.await(15, TimeUnit.SECONDS);
            return sample.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lectura de métricas Docker interrumpida", e);
        } finally {
            try {
                callback.close();
            } catch (IOException e) {
                log.debug("No se pudo cerrar callback de métricas Docker", e);
            }
        }
    }


     /**
     * Inicia el streaming de logs de un contenedor (CU-07). Devuelve el
     * ResultCallback para que el llamador lo cierre cuando ya no lo necesite
     * (ej. cuando el cliente WebSocket se desconecta) — de lo contrario el
     * stream queda abierto indefinidamente y se filtra un hilo.
     */
    public ResultCallback<Frame> streamLogs(
        String dockerContainerId,
        Consumer<Frame> onFrame) {

    ResultCallback.Adapter<Frame> callback =
            new ResultCallback.Adapter<>() {

                @Override
                public void onNext(Frame frame) {
                    onFrame.accept(frame);
                }

                @Override
                public void onComplete() {
                    log.info(
                            ">>> Streaming de logs Docker finalizado: container={}",
                            dockerContainerId
                    );
                }

                @Override
                public void onError(Throwable throwable) {
                    if (throwable instanceof com.github.dockerjava.api.exception.NotFoundException) {

                        log.warn(
                                ">>> El contenedor Docker {} ya no existe. " +
                                "No se pueden obtener sus logs.",
                                dockerContainerId
                        );

                        return;
                    }

                    log.error(
                            ">>> Error haciendo streaming de logs Docker del contenedor {}",
                            dockerContainerId,
                            throwable
                    );
                }
            };

    dockerClient.logContainerCmd(dockerContainerId)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTailAll()
            .exec(callback);

    return callback;
}

    /**
     * Cierra el cliente de Docker.
     * 
     * @throws IOException
     */
    @PreDestroy
    public void close() throws IOException {
        httpClient.close();
    }
}
