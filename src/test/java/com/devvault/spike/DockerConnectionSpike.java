package com.devvault.spike;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.github.dockerjava.core.DefaultDockerClientConfig;
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientConfig;
import org.testcontainers.shaded.com.github.dockerjava.core.DockerClientImpl;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

/**
 * SPIKE — no es parte del diseño final, es una prueba desechable para
 * validar el riesgo técnico anotado en DevVault-Roadmap.md antes de
 * construir el módulo runtime completo: ¿Java puede hablar con Docker
 * Engine en esta máquina (Windows + Docker Desktop)?
 *
 * Si este test pasa listando tus contenedores reales, confirma que el
 * enfoque de docker-java funciona y podemos construir StartProjectUseCase,
 * HealthCheckService, etc. encima con confianza.
 */

class DockerConnectionSpike {
    @Test 
    void deberiaConectarseADockerYListarContenedores() {
        // En Windows con Docker Desktop, el host por defecto suele resolverse
        // solo, pero si falla, prueba explícitamente con:
        // "npipe:////./pipe/docker_engine"
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .build();
 
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(10))
                .build();
 
        DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);
 
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
 
        System.out.println(">>> Conexión a Docker exitosa. Contenedores encontrados: " + containers.size());
        containers.forEach(c ->
                System.out.println(">>> " + c.getNames()[0] + " | imagen=" + c.getImage() + " | estado=" + c.getState())
        );
    }
}
