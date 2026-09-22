package com.devvault.automation.api;

import com.devvault.automation.application.AutomationRuleService;
import com.devvault.automation.application.dto.CreateRuleRequest;
import com.devvault.automation.application.dto.RuleResponse;
import com.devvault.automation.application.dto.UpdateRuleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/automation/rules")
public class AutomationRuleController {

    private final AutomationRuleService ruleService;

    public AutomationRuleController(AutomationRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping
    public ResponseEntity<RuleResponse> create(@Valid @RequestBody CreateRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(request));
    }

    @GetMapping
    public List<RuleResponse> findAll() {
        return ruleService.findAll();
    }

    @GetMapping("/{id}")
    public RuleResponse findById(@PathVariable UUID id) {
        return ruleService.findById(id);
    }

    @PatchMapping("/{id}")
    public RuleResponse update(@PathVariable UUID id, @RequestBody UpdateRuleRequest request) {
        return ruleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ruleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}