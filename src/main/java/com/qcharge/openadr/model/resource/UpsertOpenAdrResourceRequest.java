package com.qcharge.openadr.model.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertOpenAdrResourceRequest(
        @NotBlank
        @Size(max = 255)
        String chargePointIdentity,

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,49}")
        String chargePointUuid,

        @Positive
        Long maxPowerWatts
) {
}
