package com.qcharge.openadr.controller;

import com.qcharge.openadr.model.resource.OpenAdrResourceResponse;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusRequest;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusResponse;
import com.qcharge.openadr.model.resource.UpsertOpenAdrResourceRequest;
import com.qcharge.openadr.models.constants.Constants;
import com.qcharge.openadr.security.InternalApiKeyValidator;
import com.qcharge.openadr.service.resource.OpenAdrResourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/openadr/v1/resources/charge-points")
@RequiredArgsConstructor
public class OpenAdrResourceController {

    private final OpenAdrResourceService resourceService;
    private final InternalApiKeyValidator apiKeyValidator;

    @PutMapping("/{chargePointPk}")
    public ResponseEntity<OpenAdrResourceResponse> upsert(
            @PathVariable @Positive Integer chargePointPk,
            @Valid @RequestBody UpsertOpenAdrResourceRequest request,
            @RequestHeader(value = Constants.KEY_HEADER, required = false) String key
    ) {
        apiKeyValidator.requireValid(key);
        return ResponseEntity.ok(resourceService.upsert(chargePointPk, request));
    }

    @GetMapping("/{chargePointPk}")
    public ResponseEntity<OpenAdrResourceResponse> get(
            @PathVariable @Positive Integer chargePointPk,
            @RequestHeader(value = Constants.KEY_HEADER, required = false) String key
    ) {
        apiKeyValidator.requireValid(key);
        return ResponseEntity.ok(resourceService.get(chargePointPk));
    }

    @DeleteMapping("/{chargePointPk}")
    public ResponseEntity<Void> disable(
            @PathVariable @Positive Integer chargePointPk,
            @RequestHeader(value = Constants.KEY_HEADER, required = false) String key
    ) {
        apiKeyValidator.requireValid(key);
        resourceService.disable(chargePointPk);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/status")
    public ResponseEntity<OpenAdrResourceStatusResponse> statuses(
            @Valid @RequestBody OpenAdrResourceStatusRequest request,
            @RequestHeader(value = Constants.KEY_HEADER, required = false) String key
    ) {
        apiKeyValidator.requireValid(key);
        return ResponseEntity.ok(resourceService.statuses(request.chargePointPks()));
    }
}
