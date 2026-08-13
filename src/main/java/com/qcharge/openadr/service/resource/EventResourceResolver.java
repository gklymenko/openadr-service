package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.power.EndDeviceAssetType;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.service.event.EventValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventResourceResolver {

    public static final String DEVICE_CLASS_EVSE = "EVSE";

    private static final Set<String> STANDARD_DEVICE_CLASSES = Set.of(
            "thermostat",
            "strip_heater",
            "baseboard_heater",
            "water_heater",
            "pool_pump",
            "sauna",
            "hot_tub",
            "smart_appliance",
            "irrigation_pump",
            "managed_commercial_and_industrial_loads",
            "simple_residential_on_off_loads",
            "exterior_lighting",
            "interior_lighting",
            "electric_vehicle",
            "generation_systems",
            "load_control_switch",
            "smart_inverter",
            "evse",
            "resu",
            "energy_management_system",
            "smart_energy_module",
            "storage"
    );

    private final OpenAdrResourceRepository repository;
    private final OpenAdrProperties properties;

    @Transactional(readOnly = true)
    public ResolvedEventTarget resolveEventTarget(OadrEvent event, String venId) {
        if (event == null || event.getEiEvent() == null) {
            throw new IllegalArgumentException("eiEvent is required");
        }

        EiTargetType target = event.getEiEvent().getEiTarget();
        if (target == null || !hasAnyTarget(target)) {
            if (!properties.getEvent().isAllowUntargetedEvents()) {
                throw new TargetMismatchException("eiTarget is missing or empty");
            }
            return new ResolvedEventTarget(toResolved(repository.findAllByEnabledTrue()));
        }

        boolean venMatches = containsIgnoreCase(target.getVenID(), venId);
        List<String> requestedResourceIds = nonBlank(target.getResourceID());
        List<OpenAdrResource> matchedByResourceId = requestedResourceIds.isEmpty()
                ? List.of()
                : repository.findAllByResourceIdInAndEnabledTrue(requestedResourceIds);

        if (!venMatches && matchedByResourceId.isEmpty()) {
            throw new TargetMismatchException(
                    "Event target mismatch. venIDs=%s, resourceIDs=%s"
                            .formatted(target.getVenID(), target.getResourceID())
            );
        }

        Map<String, OpenAdrResource> resources = new LinkedHashMap<>();
        if (venMatches) {
            repository.findAllByEnabledTrue()
                    .forEach(resource -> resources.put(resource.getResourceId(), resource));
        }
        matchedByResourceId.forEach(resource -> resources.put(resource.getResourceId(), resource));

        return new ResolvedEventTarget(toResolved(resources.values()));
    }

    public List<ResolvedResource> resolveSignalTarget(
            OadrEvent event,
            String selectedSignalId,
            ResolvedEventTarget eventTarget
    ) {
        EiEventSignalType signal = findSignal(event, selectedSignalId);
        EiTargetType signalTarget = signal.getEiTarget();

        if (signalTarget == null) {
            return requireResources(eventTarget.resources(), selectedSignalId, List.of());
        }

        if (hasNonDeviceClassTarget(signalTarget)) {
            throw new EventValidationException(
                    "Only endDeviceAsset is allowed in a signal-level eiTarget",
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }

        List<String> deviceClasses = signalTarget.getEndDeviceAsset().stream()
                .map(EndDeviceAssetType::getMrid)
                .map(this::requireValidDeviceClass)
                .toList();

        if (deviceClasses.isEmpty()) {
            throw new EventValidationException(
                    "signal-level eiTarget must be omitted when endDeviceAsset is absent",
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }

        List<ResolvedResource> matches = deviceClasses.stream()
                .anyMatch(DEVICE_CLASS_EVSE::equalsIgnoreCase)
                ? eventTarget.resources()
                : List.of();

        return requireResources(matches, selectedSignalId, deviceClasses);
    }

    public Map<String, List<ResolvedResource>> resolveSignalTargets(
            OadrEvent event,
            Collection<String> signalIds,
            ResolvedEventTarget eventTarget
    ) {
        Map<String, List<ResolvedResource>> resolved = new LinkedHashMap<>();
        signalIds.forEach(signalId -> resolved.put(
                signalId,
                resolveSignalTarget(event, signalId, eventTarget)
        ));
        return Map.copyOf(resolved);
    }

    private List<ResolvedResource> requireResources(
            List<ResolvedResource> resources,
            String signalId,
            List<String> requestedDeviceClasses
    ) {
        if (resources.isEmpty()) {
            throw new EventValidationException(
                    "Unable to resolve a target resource for signal %s and deviceClasses=%s"
                            .formatted(signalId, requestedDeviceClasses),
                    ApplicationLayerErrorCodes.DEPLOYMENT_ERROR_OTHER
            );
        }
        return List.copyOf(resources);
    }

    private EiEventSignalType findSignal(OadrEvent event, String signalId) {
        if (event == null
                || event.getEiEvent() == null
                || event.getEiEvent().getEiEventSignals() == null) {
            throw new EventValidationException(
                    "Unable to locate selected signal " + signalId,
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }

        return event.getEiEvent().getEiEventSignals().getEiEventSignal().stream()
                .filter(signal -> signalId != null && signalId.equals(signal.getSignalID()))
                .findFirst()
                .orElseThrow(() -> new EventValidationException(
                        "Unable to locate selected signal " + signalId,
                        ApplicationLayerErrorCodes.INVALID_DATA
                ));
    }

    private String requireValidDeviceClass(String value) {
        if (value == null || value.isBlank()) {
            throw new EventValidationException(
                    "endDeviceAsset.mrid must not be blank",
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        boolean extension = normalized.startsWith("x-") && normalized.length() > 2;
        if (!STANDARD_DEVICE_CLASSES.contains(normalized) && !extension) {
            throw new EventValidationException(
                    "Unsupported endDeviceAsset.mrid: " + value,
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }
        return value.trim();
    }

    private List<ResolvedResource> toResolved(Collection<OpenAdrResource> resources) {
        return resources.stream()
                .sorted(Comparator.comparing(OpenAdrResource::getChargePointPk))
                .map(ResolvedResource::from)
                .toList();
    }

    private List<String> nonBlank(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return expected != null && !expected.isBlank() && values.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private boolean hasAnyTarget(EiTargetType target) {
        return !target.getVenID().isEmpty()
                || !target.getResourceID().isEmpty()
                || !target.getGroupID().isEmpty()
                || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty()
                || !target.getAggregatedPnode().isEmpty()
                || !target.getEndDeviceAsset().isEmpty()
                || !target.getMeterAsset().isEmpty()
                || !target.getPnode().isEmpty()
                || !target.getServiceArea().isEmpty()
                || !target.getServiceDeliveryPoint().isEmpty()
                || !target.getServiceLocation().isEmpty()
                || !target.getTransportInterface().isEmpty();
    }

    private boolean hasNonDeviceClassTarget(EiTargetType target) {
        return !target.getVenID().isEmpty()
                || !target.getResourceID().isEmpty()
                || !target.getGroupID().isEmpty()
                || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty()
                || !target.getAggregatedPnode().isEmpty()
                || !target.getMeterAsset().isEmpty()
                || !target.getPnode().isEmpty()
                || !target.getServiceArea().isEmpty()
                || !target.getServiceDeliveryPoint().isEmpty()
                || !target.getServiceLocation().isEmpty()
                || !target.getTransportInterface().isEmpty();
    }

    public record ResolvedEventTarget(List<ResolvedResource> resources) {
        public ResolvedEventTarget {
            resources = List.copyOf(resources);
        }
    }

    public record ResolvedResource(
            Integer chargePointPk,
            String chargePointIdentity,
            String chargePointUuid,
            String resourceId,
            Long maxPowerWatts
    ) {
        private static ResolvedResource from(OpenAdrResource resource) {
            return new ResolvedResource(
                    resource.getChargePointPk(),
                    resource.getChargePointIdentity(),
                    resource.getChargePointUuid(),
                    resource.getResourceId(),
                    resource.getMaxPowerWatts()
            );
        }
    }
}
