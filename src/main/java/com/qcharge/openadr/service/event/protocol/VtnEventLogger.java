package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.strm.StreamPayloadBaseType;
import com.qcharge.openadr.model.oadr20b.xcal.Properties;
import jakarta.xml.bind.JAXBElement;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VtnEventLogger {

    public static void logReceivedEvents(OadrDistributeEventType distributeEvent) {
        if (distributeEvent == null) {
            log.warn("Received null oadrDistributeEvent");
            return;
        }

        List<OadrEvent> events = distributeEvent.getOadrEvent();
        log.info(
                "Received OpenADR events from VTN. vtnId={}, requestId={}, eventCount={}",
                distributeEvent.getVtnID(),
                distributeEvent.getRequestID(),
                events.size()
        );

        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            logEvent(distributeEvent.getRequestID(), eventIndex, events.get(eventIndex));
        }
    }

    private static void logEvent(String requestId, int eventIndex, OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            log.warn(
                    "Received malformed VTN event. requestId={}, eventIndex={}, reason=missing eiEvent",
                    requestId,
                    eventIndex
            );
            return;
        }

        var event = oadrEvent.getEiEvent();
        EventDescriptorType descriptor = event.getEventDescriptor();
        String eventId = descriptor != null ? descriptor.getEventID() : null;
        Properties activePeriod = event.getEiActivePeriod() != null
                ? event.getEiActivePeriod().getProperties()
                : null;

        log.info(
                "VTN event. requestId={}, eventIndex={}, eventId={}, status={}, "
                        + "modificationNumber={}, priority={}, testEvent={}, responseRequired={}, "
                        + "marketContext={}, start={}, duration={}, targets={}",
                requestId,
                eventIndex,
                eventId,
                descriptor != null ? descriptor.getEventStatus() : null,
                descriptor != null ? descriptor.getModificationNumber() : null,
                descriptor != null ? descriptor.getPriority() : null,
                descriptor != null ? descriptor.getTestEvent() : null,
                oadrEvent.getOadrResponseRequired(),
                marketContext(descriptor),
                activePeriodStart(activePeriod),
                activePeriodDuration(activePeriod),
                targetSummary(event.getEiTarget())
        );

        if (event.getEiEventSignals() == null) {
            log.warn("VTN event has no signals. eventId={}", eventId);
            return;
        }

        List<EiEventSignalType> signals = event.getEiEventSignals().getEiEventSignal();
        log.info("VTN event signals. eventId={}, signalCount={}", eventId, signals.size());

        for (EiEventSignalType signal : signals) {
            logSignal(eventId, signal);
        }
    }

    private static void logSignal(String eventId, EiEventSignalType signal) {
        if (signal == null) {
            log.warn("VTN event contains null signal. eventId={}", eventId);
            return;
        }

        List<IntervalType> intervals = signal.getIntervals() != null
                ? signal.getIntervals().getInterval()
                : List.of();

        log.info(
                "VTN event signal. eventId={}, signalId={}, signalName={}, signalType={}, "
                        + "currentValue={}, unit={}, intervalCount={}",
                eventId,
                signal.getSignalID(),
                signal.getSignalName(),
                signal.getSignalType(),
                currentValue(signal),
                itemBaseName(signal),
                intervals.size()
        );

        for (int intervalIndex = 0; intervalIndex < intervals.size(); intervalIndex++) {
            IntervalType interval = intervals.get(intervalIndex);
            log.info(
                    "VTN event interval. eventId={}, signalId={}, intervalIndex={}, "
                            + "uid={}, start={}, duration={}, values={}",
                    eventId,
                    signal.getSignalID(),
                    intervalIndex,
                    intervalUid(interval),
                    intervalStart(interval),
                    intervalDuration(interval),
                    intervalValues(interval)
            );
        }
    }

    private static Object currentValue(EiEventSignalType signal) {
        if (signal.getCurrentValue() == null
                || signal.getCurrentValue().getPayloadFloat() == null) {
            return null;
        }
        return signal.getCurrentValue().getPayloadFloat().getValue();
    }

    private static String itemBaseName(EiEventSignalType signal) {
        return signal.getItemBase() != null
                ? signal.getItemBase().getName().getLocalPart()
                : null;
    }

    private static List<Object> intervalValues(IntervalType interval) {
        if (interval == null) {
            return List.of();
        }

        return interval.getStreamPayloadBase().stream()
                .map(JAXBElement::getValue)
                .map(VtnEventLogger::payloadValue)
                .toList();
    }

    private static Object payloadValue(StreamPayloadBaseType payload) {
        if (payload instanceof SignalPayloadType signalPayload
                && signalPayload.getPayloadBase() != null) {
            Object value = signalPayload.getPayloadBase().getValue();
            if (value instanceof PayloadFloatType payloadFloat) {
                return payloadFloat.getValue();
            }
            return value != null ? value.getClass().getSimpleName() : null;
        }

        return payload != null ? payload.getClass().getSimpleName() : null;
    }

    private static String marketContext(EventDescriptorType descriptor) {
        return descriptor != null
                && descriptor.getEiMarketContext() != null
                ? descriptor.getEiMarketContext().getMarketContext()
                : null;
    }

    private static String activePeriodStart(Properties activePeriod) {
        return activePeriod != null
                && activePeriod.getDtstart() != null
                && activePeriod.getDtstart().getDateTime() != null
                ? activePeriod.getDtstart().getDateTime().toXMLFormat()
                : null;
    }

    private static String activePeriodDuration(Properties activePeriod) {
        return activePeriod != null && activePeriod.getDuration() != null
                ? activePeriod.getDuration().getDuration()
                : null;
    }

    private static String intervalUid(IntervalType interval) {
        return interval != null && interval.getUid() != null
                ? interval.getUid().getText()
                : null;
    }

    private static String intervalStart(IntervalType interval) {
        return interval != null
                && interval.getDtstart() != null
                && interval.getDtstart().getDateTime() != null
                ? interval.getDtstart().getDateTime().toXMLFormat()
                : null;
    }

    private static String intervalDuration(IntervalType interval) {
        return interval != null && interval.getDuration() != null
                ? interval.getDuration().getDuration()
                : null;
    }

    private static String targetSummary(EiTargetType target) {
        if (target == null) {
            return "none";
        }

        return "venIds=%s, resourceIds=%s, groupIds=%s, groupNames=%s, partyIds=%s"
                .formatted(
                        target.getVenID(),
                        target.getResourceID(),
                        target.getGroupID(),
                        target.getGroupName(),
                        target.getPartyID()
                );
    }
}
