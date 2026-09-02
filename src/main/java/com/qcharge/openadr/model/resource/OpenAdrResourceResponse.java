package com.qcharge.openadr.model.resource;

import com.qcharge.openadr.model.entity.OpenAdrResource;

public record OpenAdrResourceResponse(
        String venKey,
        Integer chargePointPk,
        String chargePointIdentity,
        String chargePointUuid,
        String resourceId,
        String deviceClass,
        boolean enabled,
        Long maxPowerWatts
) {
    public static final String EVSE_DEVICE_CLASS = "EVSE";

    public static OpenAdrResourceResponse from(OpenAdrResource resource) {
        return new OpenAdrResourceResponse(
                resource.getVenKey(),
                resource.getChargePointPk(),
                resource.getChargePointIdentity(),
                resource.getChargePointUuid(),
                resource.getResourceId(),
                EVSE_DEVICE_CLASS,
                resource.isEnabled(),
                resource.getMaxPowerWatts()
        );
    }
}
