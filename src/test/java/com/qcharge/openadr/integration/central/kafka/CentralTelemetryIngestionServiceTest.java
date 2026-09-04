package com.qcharge.openadr.integration.central.kafka;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValues;
import com.qcharge.openadr.model.entity.ConnectorTelemetryState;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.entity.ResourceTelemetryStatus;
import com.qcharge.openadr.repository.ConnectorTelemetryStateRepository;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.repository.ResourceTelemetryStatusRepository;
import com.qcharge.openadr.service.report.telemetry.ResourceTelemetrySnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentralTelemetryIngestionServiceTest {

    @Mock
    private OpenAdrResourceRepository resourceRepository;
    @Mock
    private ConnectorTelemetryStateRepository connectorStateRepository;
    @Mock
    private ResourceTelemetryStatusRepository statusRepository;
    @Mock
    private CentralTelemetryMessageMapper mapper;
    @Mock
    private ResourceTelemetrySnapshotService snapshotService;

    private CentralMessageHandler service;
    private OpenAdrResource resource;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getVen().setKey("primary");
        service = new CentralMessageHandler(
                resourceRepository,
                connectorStateRepository,
                statusRepository,
                mapper,
                snapshotService,
                properties
        );

        resource = new OpenAdrResource();
        resource.setId(10L);
        resource.setChargePointIdentity("DC890000");
        resource.setResourceId("charger-10");
        resource.setEnabled(true);
    }

    @Test
    void staleUnkeyedKafkaMessageCannotOverwriteNewerConnectorState() {
        Instant newer = Instant.parse("2026-09-03T05:09:00Z");
        Instant stale = newer.minusSeconds(30);
        ConnectorTelemetryState state = new ConnectorTelemetryState();
        state.setResource(resource);
        state.setConnectorNumber(2);
        state.setPowerKw(new BigDecimal("105.0"));
        state.setPowerCapturedAt(newer);
        state.setEnergyRegisterKwh(new BigDecimal("7.4"));
        state.setEnergyCapturedAt(newer);

        ResourceTelemetryStatus status = new ResourceTelemetryStatus();
        status.setResource(resource);
        status.setOnline(false);
        status.setStatusCapturedAt(newer);

        MeterValues message = new MeterValues(
                "METER_VALUE",
                "DC890000",
                2,
                3666,
                List.of()
        );
        when(mapper.normalize(message)).thenReturn(List.of(
                new NormalizedMeterReading(
                        stale,
                        new BigDecimal("90.0"),
                        new BigDecimal("6.9")
                )
        ));
        when(resourceRepository.lockEnabledByChargePointIdentity("primary", "DC890000"))
                .thenReturn(Optional.of(resource));
        when(connectorStateRepository.findByResource_IdAndConnectorNumber(10L, 2))
                .thenReturn(Optional.of(state));
        when(statusRepository.findByResource_Id(10L)).thenReturn(Optional.of(status));

        assertEquals(IngestionOutcome.STALE_OR_DUPLICATE, service.handleMeterValues(message));
        assertEquals(new BigDecimal("105.0"), state.getPowerKw());
        assertEquals(new BigDecimal("7.4"), state.getEnergyRegisterKwh());
        verify(connectorStateRepository, never()).save(state);
        verify(snapshotService, never()).capture(resource, stale, true);
    }

    @Test
    void disconnectedWinsWhenStatusMessagesHaveTheSameTimestamp() {
        Instant timestamp = Instant.parse("2026-09-03T05:09:00Z");
        ResourceTelemetryStatus status = new ResourceTelemetryStatus();
        status.setResource(resource);
        status.setOnline(true);
        status.setStatusCapturedAt(timestamp);

        when(resourceRepository.lockEnabledByChargePointIdentity("primary", "DC890000"))
                .thenReturn(Optional.of(resource));
        when(statusRepository.findByResource_Id(10L)).thenReturn(Optional.of(status));

        assertEquals(
                IngestionOutcome.APPLIED,
                service.handleAvailability("DC890000", false, timestamp)
        );
        verify(statusRepository).save(status);
        verify(snapshotService).capture(resource, timestamp, false);
    }
}
