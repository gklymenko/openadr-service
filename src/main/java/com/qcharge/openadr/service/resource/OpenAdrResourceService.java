package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ResourceConflictException;
import com.qcharge.openadr.exceptions.ResourceNotFoundException;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.resource.OpenAdrResourceResponse;
import com.qcharge.openadr.model.resource.OpenAdrResourceStatusResponse;
import com.qcharge.openadr.model.resource.UpsertOpenAdrResourceRequest;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenAdrResourceService {

    private static final String RESOURCE_ID_PREFIX = "qcharge-evse-";

    private final OpenAdrResourceRepository repository;
    private final OpenAdrProperties properties;

    @Transactional
    public OpenAdrResourceResponse upsert(
            Integer chargePointPk,
            UpsertOpenAdrResourceRequest request
    ) {
        validateUniqueIdentity(chargePointPk, request);

        OpenAdrResource resource = repository.findByChargePointPk(chargePointPk)
                .map(this::requireActiveVen)
                .map(existing -> requireSameUuid(existing, request.chargePointUuid()))
                .orElseGet(() -> newResource(chargePointPk, request.chargePointUuid()));

        resource.setChargePointIdentity(request.chargePointIdentity());
        resource.setChargePointUuid(request.chargePointUuid());
        resource.setMaxPowerWatts(request.maxPowerWatts());
        resource.setEnabled(true);

        try {
            return OpenAdrResourceResponse.from(repository.saveAndFlush(resource));
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceConflictException(
                    "Charge point is already assigned to another OpenADR resource.",
                    ex
            );
        }
    }

    @Transactional(readOnly = true)
    public OpenAdrResourceResponse get(Integer chargePointPk) {
        return repository.findByVenKeyAndChargePointPk(activeVenKey(), chargePointPk)
                .map(OpenAdrResourceResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OpenADR resource for charge point " + chargePointPk + " was not found."
                ));
    }

    @Transactional
    public void disable(Integer chargePointPk) {
        repository.findByVenKeyAndChargePointPk(activeVenKey(), chargePointPk)
                .ifPresent(resource -> {
                    resource.setEnabled(false);
                    repository.save(resource);
                });
    }

    @Transactional(readOnly = true)
    public OpenAdrResourceStatusResponse statuses(List<Integer> chargePointPks) {
        Map<Integer, OpenAdrResource> resourcesByChargePoint = new LinkedHashMap<>();
        repository.findAllByVenKeyAndChargePointPkIn(activeVenKey(), chargePointPks)
                .forEach(resource -> resourcesByChargePoint.put(resource.getChargePointPk(), resource));

        List<OpenAdrResourceStatusResponse.ResourceStatus> statuses = chargePointPks.stream()
                .distinct()
                .map(chargePointPk -> toStatus(
                        chargePointPk,
                        resourcesByChargePoint.get(chargePointPk)
                ))
                .toList();

        return new OpenAdrResourceStatusResponse(statuses);
    }

    private OpenAdrResource newResource(Integer chargePointPk, String chargePointUuid) {
        OpenAdrResource resource = new OpenAdrResource();
        resource.setVenKey(activeVenKey());
        resource.setChargePointPk(chargePointPk);
        resource.setResourceId(RESOURCE_ID_PREFIX + chargePointUuid);
        return resource;
    }

    private OpenAdrResource requireActiveVen(OpenAdrResource resource) {
        if (!activeVenKey().equals(resource.getVenKey())) {
            throw new ResourceConflictException(
                    "Charge point is already assigned to logical VEN " + resource.getVenKey()
            );
        }
        return resource;
    }

    private String activeVenKey() {
        return properties.getVen().getKey();
    }

    private OpenAdrResource requireSameUuid(OpenAdrResource resource, String requestedUuid) {
        if (!resource.getChargePointUuid().equals(requestedUuid)) {
            throw new ResourceConflictException(
                    "Charge point UUID cannot be changed for an existing OpenADR resource."
            );
        }
        return resource;
    }

    private void validateUniqueIdentity(
            Integer chargePointPk,
            UpsertOpenAdrResourceRequest request
    ) {
        if (repository.existsByChargePointIdentityAndChargePointPkNot(
                request.chargePointIdentity(),
                chargePointPk
        )) {
            throw new ResourceConflictException(
                    "Charge point identity is already assigned to another OpenADR resource."
            );
        }

        if (repository.existsByChargePointUuidAndChargePointPkNot(
                request.chargePointUuid(),
                chargePointPk
        )) {
            throw new ResourceConflictException(
                    "Charge point UUID is already assigned to another OpenADR resource."
            );
        }
    }

    private OpenAdrResourceStatusResponse.ResourceStatus toStatus(
            Integer chargePointPk,
            OpenAdrResource resource
    ) {
        if (resource == null) {
            return new OpenAdrResourceStatusResponse.ResourceStatus(
                    activeVenKey(),
                    chargePointPk,
                    false,
                    null
            );
        }

        return new OpenAdrResourceStatusResponse.ResourceStatus(
                resource.getVenKey(),
                chargePointPk,
                resource.isEnabled(),
                resource.getResourceId()
        );
    }
}
