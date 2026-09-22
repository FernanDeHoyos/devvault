package com.devvault.runtime.infrastructure;

import com.devvault.runtime.application.StartProjectUseCase;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
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

    /**
     * Verifica si un contenedor está corriendo.
     * 
     * @param dockerContainerId ID del contenedor
     * @return true si el contenedor está corriendo, false en otro caso
     */
    public boolean isRunning(String dockerContainerId) {
        var inspect = dockerClient.inspectContainerCmd(dockerContainerId).exec();
        return Boolean.TRUE.equals(inspect.getState().getRunning());
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