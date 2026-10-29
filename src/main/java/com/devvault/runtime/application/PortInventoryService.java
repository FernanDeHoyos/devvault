package com.devvault.runtime.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.runtime.application.dto.PortOverviewResponse;
import com.devvault.runtime.application.dto.PortOwnerResponse;
import com.devvault.runtime.application.dto.PortServiceClaimResponse;
import com.devvault.runtime.application.dto.PortUsageResponse;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.LocalPortProbe;
import com.devvault.runtime.infrastructure.ServiceRepository;

@org.springframework.stereotype.Service
public class PortInventoryService {

    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;
    private final ProjectRepository projectRepository;
    private final LocalPortProbe localPortProbe;

    public PortInventoryService(ServiceRepository serviceRepository,
            ContainerRepository containerRepository,
            ProjectRepository projectRepository,
            LocalPortProbe localPortProbe) {
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.projectRepository = projectRepository;
        this.localPortProbe = localPortProbe;
    }

    @Transactional(readOnly = true)
    public PortOverviewResponse overview() {
        List<Service> services = serviceRepository.findAll().stream()
                .filter(service -> service.getPort() != null
                        && service.getPort() > 0
                        && service.getPort() <= 65535)
                .toList();
        if (services.isEmpty()) {
            return new PortOverviewResponse(true, Instant.now(), List.of());
        }

        Map<UUID, Project> projectsById = projectRepository.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        List<UUID> serviceIds = services.stream().map(Service::getId).toList();
        Map<UUID, Container> containersByServiceId = containerRepository.findByServiceIdIn(serviceIds).stream()
                .collect(Collectors.toMap(Container::getServiceId, Function.identity(), (first, ignored) -> first));
        Map<Integer, List<Service>> servicesByPort = services.stream()
                .collect(Collectors.groupingBy(Service::getPort));
        LocalPortProbe.ListenerScan scan = localPortProbe.scanListeners(servicesByPort.keySet());

        List<PortUsageResponse> ports = servicesByPort.entrySet().stream()
                .map(entry -> toPortUsage(entry.getKey(), entry.getValue(), projectsById,
                        containersByServiceId, scan))
                .sorted(Comparator.comparingInt(PortUsageResponse::port))
                .toList();
        return new PortOverviewResponse(scan.available(), Instant.now(), ports);
    }

    private PortUsageResponse toPortUsage(int port,
            List<Service> services,
            Map<UUID, Project> projectsById,
            Map<UUID, Container> containersByServiceId,
            LocalPortProbe.ListenerScan scan) {
        List<PortServiceClaimResponse> claims = services.stream()
                .map(service -> {
                    Project project = projectsById.get(service.getProjectId());
                    Container container = containersByServiceId.get(service.getId());
                    return new PortServiceClaimResponse(
                            service.getId(), service.getName(), service.getProjectId(),
                            project == null ? "Proyecto" : project.getName(),
                            service.getStatus().name(),
                            container == null || container.getKind() == null ? null : container.getKind().name());
                })
                .sorted(Comparator.comparing(PortServiceClaimResponse::projectName)
                        .thenComparing(PortServiceClaimResponse::serviceName))
                .toList();

        List<LocalPortProbe.PortListener> foundListeners = scan.listenersByPort()
                .getOrDefault(port, List.of());
        List<PortOwnerResponse> listeners = foundListeners.stream()
                .map(listener -> new PortOwnerResponse(listener.pid(), listener.processName()))
                .toList();
        long runningClaims = claims.stream().filter(claim -> ServiceStatus.RUNNING.name().equals(claim.serviceStatus())).count();

        String conflictReason = null;
        if (runningClaims > 1) {
            conflictReason = "Varios servicios activos declaran el mismo puerto.";
        } else if (scan.available() && !listeners.isEmpty() && runningClaims == 0) {
            conflictReason = "Hay un proceso escuchando, pero DevVault no registra un servicio activo en este puerto.";
        } else if (scan.available() && runningClaims > 0 && listeners.isEmpty()) {
            conflictReason = "El servicio figura activo, pero el sistema no reporta un proceso escuchando.";
        }

        boolean conflict = conflictReason != null;
        String status = conflict ? "CONFLICT"
                : !scan.available() ? "UNKNOWN"
                : listeners.isEmpty() ? "AVAILABLE" : "LISTENING";
        boolean canOpen = scan.available() && !conflict && runningClaims == 1 && !listeners.isEmpty();
        return new PortUsageResponse(port, status, conflict, conflictReason, claims, listeners, canOpen,
                "http://localhost:" + port);
    }
}
