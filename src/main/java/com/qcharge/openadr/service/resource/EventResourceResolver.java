package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.command.EventTargetCommand;
import com.qcharge.openadr.service.event.command.SignalTargetCommand;
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
    public ResolvedEventTarget resolveEventTarget(EventTargetCommand target, String venId) {
        if (target == null || !target.present()) {
            if (!properties.getEvent().isAllowUntargetedEvents()) {
                throw new TargetMismatchException("eiTarget is missing or empty");
            }
            return new ResolvedEventTarget(toResolved(repository.findAllByEnabledTrue()));
        }

        boolean venMatches = containsIgnoreCase(target.venIds(), venId);
        List<String> requestedResourceIds = nonBlank(target.resourceIds());
        List<OpenAdrResource> matchedByResourceId = requestedResourceIds.isEmpty()
                ? List.of()
                : repository.findAllByResourceIdInAndEnabledTrue(requestedResourceIds);

        if (!venMatches && matchedByResourceId.isEmpty()) {
            throw new TargetMismatchException(
                    "Event target mismatch. venIDs=%s, resourceIDs=%s"
                            .formatted(target.venIds(), target.resourceIds())
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
            EventSignalCommand signal,
            ResolvedEventTarget eventTarget
    ) {
        SignalTargetCommand signalTarget = signal.target();

        if (signalTarget == null) {
            return requireResources(eventTarget.resources(), signal.signalId(), List.of());
        }

        if (signalTarget.hasNonDeviceClassTarget()) {
            throw new EventValidationException(
                    "Only endDeviceAsset is allowed in a signal-level eiTarget",
                    ApplicationLayerErrorCodes.INVALID_DATA
            );
        }

        List<String> deviceClasses = signalTarget.endDeviceClasses().stream()
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

        return requireResources(matches, signal.signalId(), deviceClasses);
    }

    public Map<String, List<ResolvedResource>> resolveSignalTargets(
            Collection<EventSignalCommand> signals,
            ResolvedEventTarget eventTarget
    ) {
        Map<String, List<ResolvedResource>> resolved = new LinkedHashMap<>();
        signals.forEach(signal -> resolved.put(
                signal.signalId(),
                resolveSignalTarget(signal, eventTarget)
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
