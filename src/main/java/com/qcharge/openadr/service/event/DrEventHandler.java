package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.OptReasonEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.opt.OptService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrEventHandler {

    private final OpenAdrProperties properties;
    private final DrEventRepository drEventRepository;
    private final VtnTransportService transportService;
    private final OptService optService;

    public void handle(OadrDistributeEventType distributeEvent) {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(requestId, 200)
                .build();

        var createdEventBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, venId);

        for (OadrEvent oadrEvent : distributeEvent.getOadrEvent()) {
            var descriptor = oadrEvent.getEiEvent().getEventDescriptor();
            String eventId = descriptor.getEventID();
            long modNumber = descriptor.getModificationNumber();

            log.info("Processing event: {}, status: {}, modNumber: {}",
                    eventId, descriptor.getEventStatus(), modNumber);

            saveOrUpdateEvent(oadrEvent);

            OptTypeType optType = determineOptType(oadrEvent);

            if (optType == OptTypeType.OPT_OUT) {
                optService.optOutFromEvent(
                        eventId,
                        modNumber,
                        OptReasonEnumeratedType.NOT_PARTICIPATING
                );
            }

            EventResponse eventResponse = Oadr20bEiEventBuilders
                    .newOadr20bCreatedEventEventResponseBuilder(
                            eventId,
                            modNumber,
                            requestId,
                            200,
                            OptTypeType.OPT_IN
                    )
                    .build();

            createdEventBuilder.addEventResponse(eventResponse);
        }

        OadrCreatedEventType createdEvent = createdEventBuilder.build();

        transportService.send(Oadr20bUrlPath.EI_EVENT_SERVICE, createdEvent);
        log.info("Sent oadrCreatedEvent for {} events",
                distributeEvent.getOadrEvent().size());
    }

    private void saveOrUpdateEvent(OadrEvent oadrEvent) {
        var descriptor = oadrEvent.getEiEvent().getEventDescriptor();
        String eventId = descriptor.getEventID();

        Optional<DrEvent> existing = drEventRepository.findByEventId(eventId);
        DrEvent drEvent = existing.orElse(new DrEvent());

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber((int) descriptor.getModificationNumber());
        drEvent.setStatus(mapStatus(descriptor.getEventStatus().value()));
        drEvent.setPriority(descriptor.getPriority() != null
                ? descriptor.getPriority().intValue() : null);
        drEvent.setStartTime(LocalDateTime.now());
        drEvent.setUpdatedAt(LocalDateTime.now());

        if (existing.isEmpty()) {
            drEvent.setCreatedAt(LocalDateTime.now());
        }

        drEventRepository.save(drEvent);
    }

    private DrEvent.EventStatus mapStatus(String status) {
        return switch (status.toUpperCase()) {
            case "FAR"       -> DrEvent.EventStatus.FAR;
            case "NEAR"      -> DrEvent.EventStatus.NEAR;
            case "ACTIVE"    -> DrEvent.EventStatus.ACTIVE;
            case "COMPLETED" -> DrEvent.EventStatus.COMPLETED;
            case "CANCELLED" -> DrEvent.EventStatus.CANCELLED;
            default          -> DrEvent.EventStatus.FAR;
        };
    }

    private OptTypeType determineOptType(OadrEvent oadrEvent) {
        // Бізнес логіка: завжди optIn для EV зарядок
        // TODO: перевіряти доступність зарядок через OCPP
        return OptTypeType.OPT_IN;
    }
}