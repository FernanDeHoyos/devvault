package com.devvault.runtime.api;

import com.devvault.runtime.application.dto.ActiveRuntimeResponse;
import com.devvault.runtime.application.dto.ServiceResponse;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Vista agregada de todo lo que está corriendo AHORA, sin importar a qué
 * proyecto pertenezca — pensada para el Dashboard, para no forzar al
 * frontend a hacer una petición de status por cada proyecto (N+1).
 */
@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeOverviewController {

    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;

    public RuntimeOverviewController(RuntimeInstanceRepository runtimeInstanceRepository,
                                      ServiceRepository serviceRepository,
                                      ContainerRepository containerRepository) {
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
    }

    @GetMapping("/active")
    public List<ActiveRuntimeResponse> active() {
        return runtimeInstanceRepository.findByOverallStatus(RuntimeStatus.RUNNING).stream()
                .map(instance -> new ActiveRuntimeResponse(
                        instance.getProjectId(),
                        instance.getOverallStatus().name(),
                        instance.getStartedAt(),
                        toServiceResponses(instance.getProjectId())
                ))
                .toList();
    }

    private List<ServiceResponse> toServiceResponses(java.util.UUID projectId) {
        return serviceRepository.findByProjectId(projectId).stream()
                .map(service -> ServiceResponse.from(
                        service,
                        containerRepository.findByServiceId(service.getId()).orElse(null)
                ))
                .toList();
    }
}