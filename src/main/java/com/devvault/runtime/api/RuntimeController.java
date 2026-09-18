package com.devvault.runtime.api;

import com.devvault.runtime.application.StartProjectUseCase;
import com.devvault.runtime.application.StopProjectUseCase;
import com.devvault.runtime.application.dto.RuntimeInstanceResponse;
import com.devvault.runtime.application.dto.ServiceResponse;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;
import com.devvault.shared.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;




import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{id}")
public class RuntimeController {

    private final StartProjectUseCase startProjectUseCase;
    private final StopProjectUseCase stopProjectUseCase;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;

    public RuntimeController(StartProjectUseCase startProjectUseCase,
                              StopProjectUseCase stopProjectUseCase,
                              RuntimeInstanceRepository runtimeInstanceRepository,
                              ServiceRepository serviceRepository,
                              ContainerRepository containerRepository) {
        this.startProjectUseCase = startProjectUseCase;
        this.stopProjectUseCase = stopProjectUseCase;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
    }

    /**
     * Inicia un proyecto.
     * 
     * @param id ID del proyecto
     * @return Instancia del runtime
     */
    @PostMapping("/start")
    public ResponseEntity<RuntimeInstanceResponse> start(@PathVariable UUID id) {
        RuntimeInstance instance = startProjectUseCase.execute(id);
        return ResponseEntity.ok(RuntimeInstanceResponse.from(instance));
    }

    /**
     * Detiene un proyecto.
     * 
     * @param id ID del proyecto
     * @return Instancia del runtime
     */
    @PostMapping("/stop")
    public ResponseEntity<RuntimeInstanceResponse> stop(@PathVariable UUID id) {
        RuntimeInstance instance = stopProjectUseCase.execute(id);
        return ResponseEntity.ok(RuntimeInstanceResponse.from(instance));
    }

    /**
     * Obtiene el estado de un proyecto.
     * 
     * @param id ID del proyecto
     * @return Instancia del runtime
     */
    @GetMapping("/status")
    public RuntimeInstanceResponse status(@PathVariable UUID id) {
        return runtimeInstanceRepository.findFirstByProjectIdOrderByStartedAtDesc(id)
                .map(RuntimeInstanceResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Este proyecto nunca se ha iniciado"));
    }

    /**
     * Obtiene los servicios de un proyecto.
     * 
     * @param id ID del proyecto
     * @return Lista de servicios
     */
    @GetMapping("/services")
    public List<ServiceResponse> services(@PathVariable UUID id) {
        List<Service> services = serviceRepository.findByProjectId(id);
        return services.stream()
                .map(service -> {
                    Container container = containerRepository.findByServiceId(service.getId()).orElse(null);
                    return ServiceResponse.from(service, container);
                })
                .toList();
    }
}