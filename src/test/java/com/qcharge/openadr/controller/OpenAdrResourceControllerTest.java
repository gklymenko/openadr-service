package com.qcharge.openadr.controller;

import com.qcharge.openadr.model.resource.OpenAdrResourceResponse;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusRequest;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusResponse;
import com.qcharge.openadr.model.resource.UpsertOpenAdrResourceRequest;
import com.qcharge.openadr.security.InternalApiKeyValidator;
import com.qcharge.openadr.service.resource.OpenAdrResourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAdrResourceControllerTest {

    @Mock
    OpenAdrResourceService resourceService;

    @Mock
    InternalApiKeyValidator apiKeyValidator;

    @Test
    void validatesInternalKeyBeforeUpsert() {
        OpenAdrResourceController controller = controller();
        UpsertOpenAdrResourceRequest request = new UpsertOpenAdrResourceRequest("CP-1");
        OpenAdrResourceResponse serviceResponse = new OpenAdrResourceResponse(
                "primary",
                10,
                "CP-1",
                "qcharge-evse-10",
                "EVSE",
                true
        );
        when(resourceService.upsert(10, request)).thenReturn(serviceResponse);

        ResponseEntity<OpenAdrResourceResponse> response = controller.upsert(
                10,
                request,
                "internal-key"
        );

        InOrder inOrder = inOrder(apiKeyValidator, resourceService);
        inOrder.verify(apiKeyValidator).requireValid("internal-key");
        inOrder.verify(resourceService).upsert(10, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(serviceResponse, response.getBody());
    }

    @Test
    void disableIsExposedAsIdempotentNoContentOperation() {
        OpenAdrResourceController controller = controller();

        ResponseEntity<Void> response = controller.disable(10, "internal-key");

        verify(apiKeyValidator).requireValid("internal-key");
        verify(resourceService).disable(10);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void delegatesBatchStatusLookup() {
        OpenAdrResourceController controller = controller();
        OpenAdrResourceStatusRequest request = new OpenAdrResourceStatusRequest(List.of(10, 20));
        OpenAdrResourceStatusResponse serviceResponse = new OpenAdrResourceStatusResponse(List.of(
                new OpenAdrResourceStatusResponse.ResourceStatus(
                        "primary", 10, true, "resource-10"
                ),
                new OpenAdrResourceStatusResponse.ResourceStatus(
                        "primary", 20, false, null
                )
        ));
        when(resourceService.statuses(request.chargePointPks())).thenReturn(serviceResponse);

        ResponseEntity<OpenAdrResourceStatusResponse> response = controller.statuses(
                request,
                "internal-key"
        );

        assertEquals(serviceResponse, response.getBody());
    }

    private OpenAdrResourceController controller() {
        return new OpenAdrResourceController(resourceService, apiKeyValidator);
    }
}
