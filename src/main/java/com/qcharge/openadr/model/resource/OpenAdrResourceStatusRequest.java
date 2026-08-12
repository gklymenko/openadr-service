package com.qcharge.openadr.model.resource;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OpenAdrResourceStatusRequest(
        @NotEmpty
        @Size(max = 500)
        List<@NotNull @Positive Integer> chargePointPks
) {
}
