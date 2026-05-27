package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenReport;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.builders.eireport.PowerRealUnitType;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.siscale.SiScaleCodeType;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final OpenAdrProperties properties;
    private final VenReportRepository reportRepository;
    private final VtnTransportService transportService;

    /**
     * Реєструє доступні метрики EV зарядок у VTN.
     * Викликається після успішної реєстрації VEN.
     */
    public void registerReports() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();
        String reportSpecId = UUID.randomUUID().toString();
        String resourceId = properties.getReport().getResourceId();

        // Descriptor 1: PowerReal (кВт) — поточна потужність зарядок
        OadrReportDescriptionType powerDescriptor =
                Oadr20bEiReportBuilders
                        .newOadr20bOadrReportDescriptionBuilder(
                                resourceId + "_power",
                                ReportEnumeratedType.READING,
                                ReadingTypeEnumeratedType.DIRECT_READ
                        )
                        .withPowerRealBase(
                                PowerRealUnitType.WATT,
                                SiScaleCodeType.KILO,
                                BigDecimal.valueOf(50.0),
                                BigDecimal.valueOf(230.0),
                                true
                        )
                        .withOadrSamplingRate("PT10S", "PT60S", false)
                        .build();

        // Descriptor 2: EnergyReal (кВт·год) — спожита енергія
        OadrReportDescriptionType energyDescriptor =
                Oadr20bEiReportBuilders
                        .newOadr20bOadrReportDescriptionBuilder(
                                resourceId + "_energy",
                                ReportEnumeratedType.READING,
                                ReadingTypeEnumeratedType.DIRECT_READ
                        )
                        .withEnergyRealBase(SiScaleCodeType.KILO)
                        .withOadrSamplingRate("PT60S", "PT300S", false)
                        .build();

        // Будуємо OadrReport з обома descriptor-ами
        OadrReportType oadrReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        reportSpecId,
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        System.currentTimeMillis()   // createdTimestamp замість duration strings
                )
                .addReportDescription(powerDescriptor)   // було addOadrReportDescription
                .addReportDescription(energyDescriptor)
                .build();

        // Будуємо oadrRegisterReport
        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(oadrReport)
                .build();

        log.info("Sending oadrRegisterReport for venId: {}, reportSpecId: {}",
                venId, reportSpecId);

        Object response = transportService.send(
                Oadr20bUrlPath.EI_REPORT_SERVICE,
                registerReport
        );

        if (response instanceof OadrRegisteredReportType registered) {
            handleRegisteredReport(registered, reportSpecId);
        } else {
            log.error("Unexpected response to oadrRegisterReport: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    private void handleRegisteredReport(
            OadrRegisteredReportType response,
            String reportSpecId) {

        String responseCode = response.getEiResponse().getResponseCode();
        if (!"200".equals(responseCode)) {
            log.error("Report registration failed, code: {}", responseCode);
            return;
        }

        VenReport report = new VenReport();
        report.setReportSpecId(reportSpecId);
        report.setReportName(ReportNameEnumeratedType.TELEMETRY_USAGE.value());
        report.setStatus(VenReport.ReportStatus.REGISTERED);
        report.setGranularitySeconds(60);
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());

        reportRepository.save(report);
        log.info("Report registered successfully, reportSpecId: {}", reportSpecId);
    }
}