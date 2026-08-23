package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

import static com.qcharge.openadr.model.enums.VenRegistrationStatus.CANCELLED;
import static com.qcharge.openadr.model.enums.VenRegistrationStatus.CANCELLING;
import static com.qcharge.openadr.model.enums.VenRegistrationStatus.REGISTERED;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenRegistrationStateService {

    private final VenRegistrationRepository registrationRepository;
    private final DrEventRepository eventRepository;
    private final VenReportRepository venReportRepository;
    private final OptScheduleRepository optScheduleRepository;

    @Transactional
    public void beginCancellation(OpenAdrSessionSnapshot session) {
        if (!tryBeginCancellation(session)) {
            throw new IllegalStateException(
                    "Cannot begin cancellation for an inactive VEN registration"
            );
        }
    }

    @Transactional(readOnly = true)
    public boolean hasCancellableRegistration(OpenAdrSessionSnapshot session) {
        if (session.registrationEntityId() == null) {
            return false;
        }

        return registrationRepository.findById(session.registrationEntityId())
                .filter(registration -> persistedRegistrationMatchesSession(registration, session))
                .map(VenRegistration::getStatus)
                .filter(status -> status == REGISTERED || status == CANCELLING)
                .isPresent();
    }

    @Transactional
    public boolean tryBeginCancellation(OpenAdrSessionSnapshot session) {
        if (session.registrationEntityId() == null) {
            return false;
        }

        Long registrationEntityId = requireRegistrationEntityId(session);

        int updated = registrationRepository.transitionStatus(
                registrationEntityId, session.venId(), session.registrationId(),
                REGISTERED, CANCELLING, Instant.now()
        );

        if (updated == 1) {
            return true;
        }

        return registrationRepository.findById(registrationEntityId)
                .filter(registration -> persistedRegistrationMatchesSession(registration, session))
                .map(VenRegistration::getStatus)
                .filter(status -> status == CANCELLING)
                .isPresent();
    }

    @Transactional
    public void completeCancellation(OpenAdrSessionSnapshot session) {
        VenRegistration registration = requireRegistration(session);
        requireMatchingSession(registration, session);

        if (registration.getStatus() != CANCELLING
                && registration.getStatus() != CANCELLED) {
            throw new IllegalStateException(
                    "Cannot complete VEN registration cancellation in status="
                            + registration.getStatus()
            );
        }

        registration.setStatus(CANCELLED);
        deleteDependentRegistrationData();
        eventRepository.deleteAll();
    }

    private void requireMatchingSession(
            VenRegistration registration,
            OpenAdrSessionSnapshot session
    ) {
        if (!persistedRegistrationMatchesSession(registration, session)) {
            throw new IllegalStateException(
                    "Persisted registration does not match OpenADR session snapshot"
            );
        }
    }

    private boolean persistedRegistrationMatchesSession(
            VenRegistration registration, OpenAdrSessionSnapshot session
    ) {
        return Objects.equals(registration.getRegistrationId(), session.registrationId())
                && Objects.equals(registration.getVenId(), session.venId()
        );
    }

    @Transactional
    public void clearDependentRegistrationData() {
        deleteDependentRegistrationData();
    }

    private void deleteDependentRegistrationData() {
        venReportRepository.deleteAll();
        optScheduleRepository.deleteAll();

        log.info("Cleared VEN report and opt schedule state");
    }

    private VenRegistration requireRegistration(OpenAdrSessionSnapshot session) {
        return registrationRepository.findById(requireRegistrationEntityId(session))
                .orElseThrow(() -> new IllegalStateException(
                        "Registration from OpenADR session snapshot was not found"
                ));
    }

    private Long requireRegistrationEntityId(OpenAdrSessionSnapshot session) {
        return Objects.requireNonNull(
                session.registrationEntityId(),
                "Registered OpenADR session must contain registrationEntityId"
        );
    }
}
