package com.devvault.runtime.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.runtime.application.PortInventoryService;
import com.devvault.runtime.application.dto.PortOverviewResponse;

@RestController
@RequestMapping("/api/v1/ports")
public class PortOverviewController {

    private final PortInventoryService portInventoryService;

    public PortOverviewController(PortInventoryService portInventoryService) {
        this.portInventoryService = portInventoryService;
    }

    @GetMapping
    public PortOverviewResponse list() {
        return portInventoryService.overview();
    }
}
