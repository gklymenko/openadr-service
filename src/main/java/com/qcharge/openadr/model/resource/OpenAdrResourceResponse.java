package com.qcharge.openadr.model.resource;

import com.qcharge.openadr.model.entity.OpenAdrResource;

public record OpenAdrResourceResponse(
        String venKey,
        Integer chargePointPk,
        String chargePointIdentity,
        String resourceId,
        String deviceClass,
        boolean enabled
) {
    public static final String EVSE_DEVICE_CLASS = "EVSE";

    public static OpenAdrResourceResponse from(OpenAdrResource resource) {
        return new OpenAdrResourceResponse(
                resource.getVenKey(),
                resource.getChargePointPk(),
                resource.getChargePointIdentity(),
                resource.getResourceId(),
                EVSE_DEVICE_CLASS,
                resource.isEnabled()
        );
    }
}
