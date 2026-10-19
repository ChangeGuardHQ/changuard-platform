package com.changeguard.change.api;

import java.util.List;
import java.util.UUID;
import com.changeguard.change.change.ChangeApplicationService;
import com.changeguard.change.change.ChangeMapper;
import com.changeguard.change.change.ChangeRecord;
import com.changeguard.change.config.OrganizationAccess;
import com.changeguard.change.config.SecurityConfig;
import com.changeguard.change.exception.ChangeNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.changeguard.change.MergeFixtures.*;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Validate authentication and tenant boundaries before any projection lookup is made. */
@WebMvcTest(ChangeController.class)
@Import({SecurityConfig.class, OrganizationAccess.class, ChangeMapper.class})
@TestPropertySource(properties = {"changeguard.api.organizations[0].organization-id=org-one",
        "changeguard.api.organizations[0].readers[0]=reader"})
class ChangeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChangeApplicationService service;
    private final ChangeRecord change = new ChangeMapper().newRecord(merged("one", "org-one", "repo", 7));

    @Test
    void unauthenticatedReadsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/changes").param("organizationId", "org-one")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void organizationQueryAndAuthenticatedNameCannotGrantUnconfiguredAccess() throws Exception {
        mvc.perform(get("/api/v1/changes").param("organizationId", "org-two").with(user("reader")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/changes").param("organizationId", "org-one").with(user("intruder")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void authorizedListUsesAStableResponseShapeAndScopedArguments() throws Exception {
        when(service.list("org-one", "repo", 0, 20)).thenReturn(new PageImpl<>(List.of(change), PageRequest.of(0, 20), 1));
        mvc.perform(get("/api/v1/changes").param("organizationId", "org-one").param("repositoryId", "repo")
                        .with(user("reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(change.getId().toString()))
                .andExpect(jsonPath("$.items[0].status").value("MERGED"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.page").value(0));
        verify(service).list("org-one", "repo", 0, 20);
    }

    @Test
    void detailAndTimelineUseProviderOccurrenceTime() throws Exception {
        when(service.find("org-one", change.getId())).thenReturn(change);
        mvc.perform(get("/api/v1/changes/" + change.getId()).param("organizationId", "org-one").with(user("reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commitSha").value(SHA));
        mvc.perform(get("/api/v1/changes/" + change.getId() + "/timeline")
                        .param("organizationId", "org-one").with(user("reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].type").value("PullRequestMerged"))
                .andExpect(jsonPath("$.entries[0].occurredAt").value(MERGED_AT.toString()));
    }

    @Test
    void unavailableScopedIdReturns404WithoutRevealingAnotherOwner() throws Exception {
        var id = UUID.randomUUID();
        when(service.find("org-one", id)).thenThrow(new ChangeNotFoundException());
        mvc.perform(get("/api/v1/changes/" + id).param("organizationId", "org-one").with(user("reader")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Change not found"));
    }

    @Test
    void missingScopeAndInvalidIdentifierOrPageTypeReturn400() throws Exception {
        mvc.perform(get("/api/v1/changes").with(user("reader"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/changes/invalid-uuid").param("organizationId", "org-one").with(user("reader")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/changes").param("organizationId", "org-one").param("page", "invalid")
                .with(user("reader"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
