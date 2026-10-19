package com.changeguard.change.api;

import java.util.UUID;
import com.changeguard.change.api.dto.*;
import com.changeguard.change.change.ChangeApplicationService;
import com.changeguard.change.change.ChangeMapper;
import com.changeguard.change.config.OrganizationAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Read-only projection API; mutations arrive through the validated event pipeline. */
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {
    private final ChangeApplicationService service;
    private final ChangeMapper mapper;
    private final OrganizationAccess access;

    public ChangeController(ChangeApplicationService service, ChangeMapper mapper, OrganizationAccess access) {
        this.service = service;
        this.mapper = mapper;
        this.access = access;
    }

    @GetMapping
    public ChangePageResponse list(@RequestParam String organizationId,
            @RequestParam(required = false) String repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        access.requireRead(authentication, organizationId);
        var result = service.list(organizationId, repositoryId, page, size);
        return new ChangePageResponse(result.getContent().stream().map(mapper::response).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    public ChangeResponse detail(@PathVariable UUID id, @RequestParam String organizationId, Authentication authentication) {
        access.requireRead(authentication, organizationId);
        return mapper.response(service.find(organizationId, id));
    }

    @GetMapping("/{id}/timeline")
    public ChangeTimelineResponse timeline(@PathVariable UUID id, @RequestParam String organizationId,
            Authentication authentication) {
        access.requireRead(authentication, organizationId);
        return mapper.timeline(service.find(organizationId, id));
    }
}
