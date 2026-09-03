package com.qcharge.openadr.model.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertOpenAdrResourceRequest(
        @NotBlank
        @Size(max = 255)
        String chargePointIdentity
) {
}
