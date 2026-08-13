package com.qcharge.openadr.support;

import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.mapping.EventEntityMapper;
import com.qcharge.openadr.service.event.mapping.EventPayloadMapper;
import com.qcharge.openadr.service.event.processing.EventCancellationService;
import com.qcharge.openadr.service.event.processing.EventProcessor;
import com.qcharge.openadr.service.event.processing.EventVersionPolicy;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.event.store.EventStore;
import com.qcharge.openadr.service.event.store.JpaEventStore;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.mapstruct.factory.Mappers;

import java.time.Clock;

/** Explicit test composition root for the VEN Event application components. */
public final class EventProtocolTestComponents {

    private EventProtocolTestComponents() {
    }

    public static EventProtocolAdapter protocolAdapter(
            DrEventRepository repository,
            VtnTransportService transportService,
            EventOptDecisionService optDecisionService,
            EventValidationService validationService,
            EventResourceResolver resourceResolver
    ) {
        return protocolAdapter(
                repository,
                transportService,
                optDecisionService,
                validationService,
                resourceResolver,
                Clock.systemUTC()
        );
    }

    public static EventProtocolAdapter protocolAdapter(
            DrEventRepository repository,
            VtnTransportService transportService,
            EventOptDecisionService optDecisionService,
            EventValidationService validationService,
            EventResourceResolver resourceResolver,
            Clock clock
    ) {
        EventStore eventStore = new JpaEventStore(repository);
        EventCancellationService cancellationService =
                new EventCancellationService(eventStore, clock);
        EventPayloadMapper payloadMapper = new EventPayloadMapper(
                Mappers.getMapper(EventEntityMapper.class),
                clock
        );
        EventProcessor processor = new EventProcessor(
                eventStore,
                new EventVersionPolicy(),
                validationService,
                resourceResolver,
                optDecisionService,
                payloadMapper,
                cancellationService,
                clock
        );
        return new EventProtocolAdapter(processor, cancellationService, transportService);
    }
}
