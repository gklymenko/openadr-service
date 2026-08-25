package com.qcharge.openadr.support;

import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.mapping.EventEntityMapper;
import com.qcharge.openadr.service.event.mapping.EventPayloadMapper;
import com.qcharge.openadr.service.event.processing.EventCancellationService;
import com.qcharge.openadr.service.event.processing.EventProcessor;
import com.qcharge.openadr.service.event.processing.EventVersionPolicy;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.event.protocol.OpenAdrEventCommandMapper;
import com.qcharge.openadr.service.event.store.EventService;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.validation.EventValidator;

import java.time.Clock;

/** Explicit test composition root for the VEN Event application components. */
public final class EventProtocolTestComponents {

    private EventProtocolTestComponents() {
    }

    public static EventProtocolAdapter protocolAdapter(
            DrEventRepository repository,
            VtnTransportService transportService,
            EventValidationService validationService,
            EventResourceResolver resourceResolver
    ) {
        return protocolAdapter(
                repository,
                transportService,
                validationService,
                resourceResolver,
                Clock.systemUTC(),
                new OpenAdrEventCommandMapper()
        );
    }

    public static EventProtocolAdapter protocolAdapter(
            DrEventRepository repository,
            VtnTransportService transportService,
            EventValidationService validationService,
            EventResourceResolver resourceResolver,
            Clock clock
    ) {
        return protocolAdapter(
                repository, transportService,
                validationService, resourceResolver, clock,
                new OpenAdrEventCommandMapper());
    }

    public static EventProtocolAdapter protocolAdapter(
            DrEventRepository repository,
            VtnTransportService transportService,
            EventValidationService validationService,
            EventResourceResolver resourceResolver,
            Clock clock,
            OpenAdrEventCommandMapper commandMapper
    ) {
        EventService eventService = new EventService(repository);
        EventCancellationService cancellationService =
                new EventCancellationService(eventService, clock);
        EventPayloadMapper payloadMapper = new EventPayloadMapper(
                new EventEntityMapper(),
                clock
        );
        EventProcessor processor = new EventProcessor(
                eventService,
                new EventVersionPolicy(),
                validationService,
                resourceResolver,
                payloadMapper,
                cancellationService,
                clock
        );
        return new EventProtocolAdapter(
                processor,
                cancellationService,
                transportService,
                new EventValidator(),
                commandMapper
        );
    }
}
