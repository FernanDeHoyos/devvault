package com.devvault.runtime.api;

import com.devvault.runtime.application.StartProjectUseCase;
import com.devvault.runtime.application.StopProjectUseCase;
import com.devvault.runtime.application.dto.RuntimeInstanceResponse;
import com.devvault.runtime.application.dto.ServiceResponse;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.devvault.shared.api.exception.ApiException;


import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{id}")
public class RuntimeController {

    private final StartProjectUseCase startProjectUseCase;
    private final StopProjectUseCase stopProjectUseCase;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ServiceRepository serviceRepository;

    public RuntimeController(StartProjectUseCase startProjectUseCase,
            StopProjectUseCase stopProjectUseCase,
            RuntimeInstanceRepository runtimeInstanceRepository,
            ServiceRepository serviceRepository) {
        this.startProjectUseCase = startProjectUseCase;
        this.stopProjectUseCase = stopProjectUseCase;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
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
        return serviceRepository.findByProjectId(id).stream()
                .map(ServiceResponse::from)
                .toList();
    }
}