package com.qcharge.openadr.model.resource;

import java.util.List;

public record OpenAdrResourceStatusResponse(
        List<ResourceStatus> resources
) {
    public record ResourceStatus(
            String venKey,
            Integer chargePointPk,
            boolean enabled,
            String resourceId
    ) {
    }
}
