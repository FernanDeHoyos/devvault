package com.devvault.runtime.application;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.ContainerKind;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuntimeReconciliationService implements ApplicationRunner {

    private final ContainerRepository containerRepository;
    private final ServiceRepository serviceRepository;
    private final RuntimeInstanceRepository runtimeInstanceRepository;


    /**
     * Cuando el servidor arranca, este método se ejecuta automáticamente para
     * verificar si hay procesos locales que deberían estar corriendo pero no lo están.
     * Si encuentra un PID que ya no existe, marca el servicio como detenido y
     * actualiza el estado del proyecto.
     * CU-05
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info(">>> Iniciando reconciliación de procesos locales...");
        List<Container> localProcesses =
                containerRepository.findByKind(ContainerKind.LOCAL_PROCESS);

        // Iteramos sobre cada contenedor local para verificar si el proceso está vivo
        for (Container container : localProcesses) {
            if (container.getPid() == null) {
                continue;
            }
            // Verificamos si el PID existe
            boolean alive = ProcessHandle.of(container.getPid())
                    .map(ProcessHandle::isAlive)
                    .orElse(false);
            if (alive) {
                log.info(">>> PID {} sigue vivo. Se conserva como activo.",container.getPid());
                continue;
            }
            log.info(">>> PID {} ya no existe. Marcando como detenido.",container.getPid());
            container.updateState("stopped");
            containerRepository.save(container);
            Service service = serviceRepository
                    .findById(container.getServiceId())
                    .orElse(null);
            if (service == null) {
                log.warn(">>> No se encontró Service {} para Container {}",container.getServiceId(),container.getId());
                continue;
            }
            service.updateStatus(ServiceStatus.STOPPED);
            serviceRepository.save(service);

            UUID projectId = service.getProjectId();
            // Buscamos la instancia más reciente del proyecto y la marcamos como detenida
            runtimeInstanceRepository
                    .findFirstByProjectIdOrderByStartedAtDesc(projectId)
                    .filter(instance ->
                            instance.getOverallStatus() == RuntimeStatus.RUNNING
                            || instance.getOverallStatus() == RuntimeStatus.STARTING)
                    .ifPresent(instance -> {
                        instance.markStopped();
                        runtimeInstanceRepository.save(instance);
                        log.info(">>> Proyecto {} marcado como STOPPED.",projectId);
                    });
        }

        log.info(">>> Reconciliación de procesos locales finalizada.");
    }
}