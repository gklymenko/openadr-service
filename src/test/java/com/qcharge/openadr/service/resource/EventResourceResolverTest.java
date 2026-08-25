package com.qcharge.openadr.service.resource;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.power.EndDeviceAssetType;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.protocol.OpenAdrEventCommandMapper;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import com.qcharge.openadr.service.validation.EventValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventResourceResolverTest extends AbstractOadrTest {

    private OpenAdrResourceRepository repository;
    private EventResourceResolver resolver;
    private OpenAdrEventCommandMapper mapper;
    private EventValidator eventValidator;

    @BeforeEach
    void setUp() {
        repository = mock(OpenAdrResourceRepository.class);
        resolver = new EventResourceResolver(repository, new OpenAdrProperties());
        mapper = new OpenAdrEventCommandMapper();
        eventValidator = new EventValidator();
    }

    @Test
    void resolvesEnabledRegistryResourceFromEventResourceId()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadEvent();
        OpenAdrResource resource = resource(10, "RES_123", true);
        when(repository.findAllByResourceIdInAndEnabledTrue(anyCollection()))
                .thenReturn(List.of(resource));

        eventValidator.validateEvent(event);
        ResolvedEventTarget result = resolver.resolveEventTarget(
                mapper.map(event).target(), "VEN-1");

        assertEquals(List.of("RES_123"), result.resources().stream()
                .map(ResolvedResource::resourceId)
                .toList());
    }

    @Test
    void multipleDeviceClassesAreOrAndEvseMatches()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadEvent();
        var signalTarget = selectedSignal(event).getEiTarget();
        signalTarget.getEndDeviceAsset().clear();
        signalTarget.getEndDeviceAsset().add(deviceClass("Thermostat"));
        signalTarget.getEndDeviceAsset().add(deviceClass("EVSE"));
        ResolvedResource resource = resolvedResource("RES_123");

        List<ResolvedResource> result = resolver.resolveSignalTarget(
                signal(event, "SIG_02"),
                new ResolvedEventTarget(List.of(resource))
        );

        assertEquals(List.of(resource), result);
    }

    @Test
    void validButUnresolvableDeviceClassReturns469()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadEvent();
        var signalTarget = selectedSignal(event).getEiTarget();
        signalTarget.getEndDeviceAsset().clear();
        signalTarget.getEndDeviceAsset().add(deviceClass("Thermostat"));

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> resolver.resolveSignalTarget(
                        signal(event, "SIG_02"),
                        new ResolvedEventTarget(List.of(resolvedResource("RES_123")))
                )
        );

        assertEquals(OpenADRResponseCode.DEPLOYMENT_ERROR_OTHER,
                exception.getResponseCode());
    }

    @Test
    void signalTargetWithResourceIdReturns454BecauseOnlyEndDeviceAssetIsAllowed()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadEvent();
        selectedSignal(event).getEiTarget().getResourceID().add("RES_123");

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> resolver.resolveSignalTarget(
                        signal(event, "SIG_02"),
                        new ResolvedEventTarget(List.of(resolvedResource("RES_123")))
                )
        );

        assertEquals(OpenADRResponseCode.INVALID_DATA, exception.getResponseCode());
    }

    @Test
    void omittedSignalTargetUsesAllEventTargetResources()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadEvent();
        selectedSignal(event).setEiTarget(null);
        ResolvedResource resource = resolvedResource("RES_123");

        assertEquals(
                List.of(resource),
                resolver.resolveSignalTarget(
                        signal(event, "SIG_02"),
                        new ResolvedEventTarget(List.of(resource))
                )
        );
    }

    private OadrEvent loadEvent() throws Oadr20bUnmarshalException {
        OadrDistributeEventType payload = jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class
        );
        return payload.getOadrEvent().getFirst();
    }

    private com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType selectedSignal(
            OadrEvent event
    ) {
        return event.getEiEvent().getEiEventSignals().getEiEventSignal().stream()
                .filter(signal -> "SIG_02".equals(signal.getSignalID()))
                .findFirst()
                .orElseThrow();
    }

    private EndDeviceAssetType deviceClass(String mrid) {
        EndDeviceAssetType asset = new EndDeviceAssetType();
        asset.setMrid(mrid);
        return asset;
    }

    private EventSignalCommand signal(OadrEvent event, String signalId) {
        eventValidator.validateEvent(event);
        return mapper.map(event).signals().stream()
                .filter(signal -> signalId.equals(signal.signalId()))
                .findFirst()
                .orElseThrow();
    }

    private OpenAdrResource resource(Integer chargePointPk, String resourceId, boolean enabled) {
        OpenAdrResource resource = new OpenAdrResource();
        resource.setChargePointPk(chargePointPk);
        resource.setChargePointIdentity("CP-" + chargePointPk);
        resource.setChargePointUuid("uuid-" + chargePointPk);
        resource.setResourceId(resourceId);
        resource.setEnabled(enabled);
        resource.setMaxPowerWatts(22_000L);
        return resource;
    }

    private ResolvedResource resolvedResource(String resourceId) {
        return new ResolvedResource(10, "CP-10", "uuid-10", resourceId, 22_000L);
    }
}
