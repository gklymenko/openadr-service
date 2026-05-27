package com.qcharge.openadr.service.opt;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OptSchedule;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiOptBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.ei.OptReasonEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptService {

    private final OpenAdrProperties properties;
    private final OptScheduleRepository optScheduleRepository;
    private final VtnTransportService transportService;

    /**
     * Opt-out з конкретної DR події.
     * Наприклад: зарядки зайняті, технічна проблема.
     */
    public void optOutFromEvent(String eventId, long modificationNumber,
                                OptReasonEnumeratedType reason) {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();
        String optId = UUID.randomUUID().toString();

        OadrCreateOptType createOpt = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        requestId,
                        venId,
                        System.currentTimeMillis(),
                        eventId,
                        modificationNumber,
                        optId,
                        OptTypeType.OPT_OUT,
                        reason
                )
                .build();

        log.info("Sending optOut for eventId: {}, reason: {}", eventId, reason);

        Object response = transportService.send(
                Oadr20bUrlPath.EI_OPT_SERVICE,
                createOpt
        );

        if (response instanceof OadrCreatedOptType created) {
            handleCreatedOpt(created, eventId, OptSchedule.OptType.OPT_OUT, reason.value());
        } else {
            log.error("Unexpected response to oadrCreateOpt: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    /**
     * Opt-in до конкретної DR події.
     */
    public void optInToEvent(String eventId, long modificationNumber) {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();
        String optId = UUID.randomUUID().toString();

        OadrCreateOptType createOpt = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        requestId,
                        venId,
                        System.currentTimeMillis(),
                        eventId,
                        modificationNumber,
                        optId,
                        OptTypeType.OPT_IN,
                        OptReasonEnumeratedType.ECONOMIC
                )
                .build();

        log.info("Sending optIn for eventId: {}", eventId);

        Object response = transportService.send(
                Oadr20bUrlPath.EI_OPT_SERVICE,
                createOpt
        );

        if (response instanceof OadrCreatedOptType created) {
            handleCreatedOpt(created, eventId, OptSchedule.OptType.OPT_IN, null);
        } else {
            log.error("Unexpected response to oadrCreateOpt: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    /**
     * Скасувати існуючий opt.
     */
    public void cancelOpt(String optId) {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        OadrCancelOptType cancelOpt = Oadr20bEiOptBuilders
                .newOadr20bCancelOptBuilder(requestId, optId, venId)
                .build();

        log.info("Sending cancelOpt for optId: {}", optId);

        Object response = transportService.send(
                Oadr20bUrlPath.EI_OPT_SERVICE,
                cancelOpt
        );

        if (response instanceof OadrCanceledOptType) {
            optScheduleRepository.findByOptId(optId).ifPresent(opt -> {
                opt.setStatus(OptSchedule.OptStatus.CANCELLED);
                opt.setUpdatedAt(LocalDateTime.now());
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

        OptSchedule opt = new OptSchedule();
        opt.setOptId(response.getOptID());
        opt.setOptType(optType);
        opt.setOptReason(reason);
        opt.setEventId(eventId);
        opt.setStatus(OptSchedule.OptStatus.ACTIVE);
        opt.setCreatedAt(LocalDateTime.now());
        opt.setUpdatedAt(LocalDateTime.now());

        optScheduleRepository.save(opt);
        log.info("Opt saved: optId={}, type={}, eventId={}",
                response.getOptID(), optType, eventId);
    }
}