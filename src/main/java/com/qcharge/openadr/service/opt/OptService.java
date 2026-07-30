package com.qcharge.openadr.service.opt;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OptSchedule;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiOptBuilders;
import com.qcharge.openadr.model.oadr20b.ei.OptReasonEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptService {

    private final OpenAdrProperties properties;
    private final OptScheduleRepository optScheduleRepository;
    private final VtnTransportService transportService;
    private final RegistrationService registrationService;

    public void optOutFromEvent(String eventId, long modificationNumber, OptReasonEnumeratedType reason) {
        log.info("Sending optOut for eventId: {}, reason: {}", eventId, reason);
        sendCreateOpt(eventId, modificationNumber, OptTypeType.OPT_OUT, reason);
    }

    public void optInToEvent(String eventId, long modificationNumber) {
        log.info("Sending optIn for eventId: {}", eventId);
        sendCreateOpt(eventId, modificationNumber, OptTypeType.OPT_IN, OptReasonEnumeratedType.ECONOMIC);
    }

    private void sendCreateOpt(String eventId, long modificationNumber,
                                OptTypeType optType, OptReasonEnumeratedType reason) {
        String venId = registrationService.currentVenId();
        String requestId = UUID.randomUUID().toString();
        String optId = UUID.randomUUID().toString();

        OadrCreateOptType createOpt = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        requestId, venId, System.currentTimeMillis(),
                        eventId, modificationNumber, optId, optType, reason
                )
                .build();

        Object response = transportService.createOpt(createOpt);

        if (response instanceof OadrCreatedOptType created) {
            OptSchedule.OptType scheduleType = optType == OptTypeType.OPT_IN
                    ? OptSchedule.OptType.OPT_IN : OptSchedule.OptType.OPT_OUT;
            handleCreatedOpt(created, eventId, scheduleType,
                    optType == OptTypeType.OPT_OUT ? reason.value() : null);
        } else {
            log.error("Unexpected response to oadrCreateOpt: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    public void cancelOpt(String optId) {
        String venId = registrationService.currentVenId();
        String requestId = UUID.randomUUID().toString();

        OadrCancelOptType cancelOpt = Oadr20bEiOptBuilders
                .newOadr20bCancelOptBuilder(requestId, optId, venId)
                .build();

        log.info("Sending cancelOpt for optId: {}", optId);

        Object response = transportService.cancelOpt(cancelOpt);

        if (response instanceof OadrCanceledOptType) {
            optScheduleRepository.findByOptId(optId).ifPresent(opt -> {
                opt.setStatus(OptSchedule.OptStatus.CANCELLED);
                opt.setUpdatedAt(Instant.now());
                optScheduleRepository.save(opt);
                log.info("Opt cancelled: {}", optId);
            });
        } else {
            log.error("Unexpected response to oadrCancelOpt: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    private void handleCreatedOpt(OadrCreatedOptType response,
                                  String eventId,
                                  OptSchedule.OptType optType,
                                  String reason) {
        String responseCode = response.getEiResponse().getResponseCode();
        if (!"200".equals(responseCode)) {
            log.error("Opt failed, code: {}, description: {}",
                    responseCode,
                    response.getEiResponse().getResponseDescription());
            return;
        }

        optScheduleRepository.save(buildOptSchedule(response.getOptID(), optType, reason, eventId));
        log.info("Opt saved: optId={}, type={}, eventId={}", response.getOptID(), optType, eventId);
    }

    private OptSchedule buildOptSchedule(String optId, OptSchedule.OptType optType,
                                          String reason, String eventId) {
        OptSchedule opt = new OptSchedule();
        opt.setOptId(optId);
        opt.setOptType(optType);
        opt.setOptReason(reason);
        opt.setEventId(eventId);
        opt.setStatus(OptSchedule.OptStatus.ACTIVE);
        opt.setCreatedAt(Instant.now());
        opt.setUpdatedAt(Instant.now());
        return opt;
    }
}
