package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.config.OpenAdrProperties;
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
        OpenAdrResourceService service = service();
        UpsertOpenAdrResourceRequest request = request("CP-1");

        when(repository.findByChargePointPk(10)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(OpenAdrResource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OpenAdrResourceResponse response = service.upsert(10, request);

        ArgumentCaptor<OpenAdrResource> captor = ArgumentCaptor.forClass(OpenAdrResource.class);
        verify(repository).saveAndFlush(captor.capture());
        OpenAdrResource saved = captor.getValue();

        assertEquals("qcharge-evse-10", saved.getResourceId());
        assertEquals("primary", saved.getVenKey());
        assertEquals("primary", response.venKey());
        assertTrue(saved.isEnabled());
        assertEquals("EVSE", response.deviceClass());
    }

    @Test
    void reenablesExistingResourceAndNormalizesResourceId() {
        OpenAdrResource existing = resource(10, "CP-OLD", "provisioned-id", false);
        OpenAdrResourceService service = service();
        when(repository.findByChargePointPk(10)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        OpenAdrResourceResponse response = service.upsert(
                10,
                request("CP-NEW")
        );

        assertTrue(response.enabled());
        assertEquals("qcharge-evse-10", response.resourceId());
        assertEquals("CP-NEW", response.chargePointIdentity());
    }

    @Test
    void rejectsReassigningChargePointFromAnotherLogicalVen() {
        OpenAdrResource existing = resource(
                10,
                "CP-1",
                "resource-1",
                true
        );
        existing.setVenKey("secondary");
        OpenAdrResourceService service = service();
        when(repository.findByChargePointPk(10)).thenReturn(Optional.of(existing));

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> service.upsert(10, request("CP-1"))
        );

        assertTrue(exception.getMessage().contains("secondary"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void disableIsIdempotentWhenResourceDoesNotExist() {
        OpenAdrResourceService service = service();
        when(repository.findByVenKeyAndChargePointPk("primary", 10))
                .thenReturn(Optional.empty());

        service.disable(10);

        verify(repository, never()).save(any());
    }

    @Test
    void statusesIncludeDisabledAndUnregisteredChargePointsInRequestOrder() {
        OpenAdrResource disabled = resource(20, "CP-20", "resource-20", false);
        OpenAdrResource enabled = resource(10, "CP-10", "resource-10", true);
        OpenAdrResourceService service = service();
        when(repository.findAllByVenKeyAndChargePointPkIn(
                "primary",
                List.of(20, 30, 10, 10)
        ))
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

    private UpsertOpenAdrResourceRequest request(String identity) {
        return new UpsertOpenAdrResourceRequest(identity);
    }

    private OpenAdrResourceService service() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getVen().setKey("primary");
        return new OpenAdrResourceService(repository, properties);
    }

    private OpenAdrResource resource(
            Integer chargePointPk,
            String identity,
            String resourceId,
            boolean enabled
    ) {
        OpenAdrResource resource = new OpenAdrResource();
        resource.setVenKey("primary");
        resource.setChargePointPk(chargePointPk);
        resource.setChargePointIdentity(identity);
        resource.setResourceId(resourceId);
        resource.setEnabled(enabled);
        return resource;
    }
}
