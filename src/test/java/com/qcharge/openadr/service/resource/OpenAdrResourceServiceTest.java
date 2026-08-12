package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.exceptions.ResourceConflictException;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.resource.OpenAdrResourceResponse;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusResponse;
import com.qcharge.openadr.model.resource.UpsertOpenAdrResourceRequest;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAdrResourceServiceTest {

    @Mock
    OpenAdrResourceRepository repository;

    @Test
    void createsEvseResourceIdAndEnablesResource() {
        OpenAdrResourceService service = new OpenAdrResourceService(repository);
        UpsertOpenAdrResourceRequest request = request("CP-1", "uuid-1", 22_000L);

        when(repository.findByChargePointPk(10)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(OpenAdrResource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OpenAdrResourceResponse response = service.upsert(10, request);

        ArgumentCaptor<OpenAdrResource> captor = ArgumentCaptor.forClass(OpenAdrResource.class);
        verify(repository).saveAndFlush(captor.capture());
        OpenAdrResource saved = captor.getValue();

        assertEquals("qcharge-evse-uuid-1", saved.getResourceId());
        assertTrue(saved.isEnabled());
        assertEquals("EVSE", response.deviceClass());
        assertEquals(22_000L, response.maxPowerWatts());
    }

    @Test
    void reenablesExistingResourceAndPreservesResourceId() {
        OpenAdrResource existing = resource(10, "CP-OLD", "uuid-1", "provisioned-id", false);
        OpenAdrResourceService service = new OpenAdrResourceService(repository);
        when(repository.findByChargePointPk(10)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        OpenAdrResourceResponse response = service.upsert(
                10,
                request("CP-NEW", "uuid-1", null)
        );

        assertTrue(response.enabled());
        assertEquals("provisioned-id", response.resourceId());
        assertEquals("CP-NEW", response.chargePointIdentity());
    }

    @Test
    void rejectsUuidChangeForExistingResource() {
        OpenAdrResource existing = resource(10, "CP-1", "uuid-1", "resource-1", true);
        OpenAdrResourceService service = new OpenAdrResourceService(repository);
        when(repository.findByChargePointPk(10)).thenReturn(Optional.of(existing));

        assertThrows(
                ResourceConflictException.class,
                () -> service.upsert(10, request("CP-1", "uuid-2", null))
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void disableIsIdempotentWhenResourceDoesNotExist() {
        OpenAdrResourceService service = new OpenAdrResourceService(repository);
        when(repository.findByChargePointPk(10)).thenReturn(Optional.empty());

        service.disable(10);

        verify(repository, never()).save(any());
    }

    @Test
    void statusesIncludeDisabledAndUnregisteredChargePointsInRequestOrder() {
        OpenAdrResource disabled = resource(20, "CP-20", "uuid-20", "resource-20", false);
        OpenAdrResource enabled = resource(10, "CP-10", "uuid-10", "resource-10", true);
        OpenAdrResourceService service = new OpenAdrResourceService(repository);
        when(repository.findAllByChargePointPkIn(List.of(20, 30, 10, 10)))
                .thenReturn(List.of(enabled, disabled));

        OpenAdrResourceStatusResponse response = service.statuses(List.of(20, 30, 10, 10));

        assertEquals(List.of(20, 30, 10), response.resources().stream()
                .map(OpenAdrResourceStatusResponse.ResourceStatus::chargePointPk)
                .toList());
        assertFalse(response.resources().get(0).enabled());
        assertFalse(response.resources().get(1).enabled());
        assertNull(response.resources().get(1).resourceId());
        assertTrue(response.resources().get(2).enabled());
    }

    private UpsertOpenAdrResourceRequest request(
            String identity,
            String uuid,
            Long maxPowerWatts
    ) {
        return new UpsertOpenAdrResourceRequest(identity, uuid, maxPowerWatts);
    }

    private OpenAdrResource resource(
            Integer chargePointPk,
            String identity,
            String uuid,
            String resourceId,
            boolean enabled
    ) {
        OpenAdrResource resource = new OpenAdrResource();
        resource.setChargePointPk(chargePointPk);
        resource.setChargePointIdentity(identity);
        resource.setChargePointUuid(uuid);
        resource.setResourceId(resourceId);
        resource.setEnabled(enabled);
        return resource;
    }
}
