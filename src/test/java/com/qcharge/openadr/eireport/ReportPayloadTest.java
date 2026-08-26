package com.qcharge.openadr.eireport;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.eireport.PowerRealUnitType;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.siscale.SiScaleCodeType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ReportPayloadTest extends AbstractOadrTest {

    @Test
    void registerReport_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        // PowerReal descriptor для EV зарядок
        OadrReportDescriptionType powerDescriptor =
                Oadr20bEiReportBuilders
                        .newOadr20bOadrReportDescriptionBuilder(
                                "ev-chargers-group-1_power",
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

        OadrReportType oadrReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        "report-spec-001",
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        System.currentTimeMillis()
                )
                .addReportDescription(powerDescriptor)
                .build();

        OadrRegisterReportType payload = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("req-001", "ven-001")
                .addOadrReport(oadrReport)
                .build();

        // Marshal → XML
        String xml = jaxbContext.marshalRoot(payload);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrRegisterReport"));
        assertTrue(xml.contains("ven-001"));
        assertTrue(xml.contains("TELEMETRY_USAGE"));
        assertTrue(xml.contains("2.0b"));

        // Unmarshal → Object
        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertNotNull(unmarshalled);
        assertInstanceOf(OadrRegisterReportType.class, unmarshalled);

        OadrRegisterReportType result = (OadrRegisterReportType) unmarshalled;
        assertEquals("ven-001", result.getVenID());
        assertEquals(1, result.getOadrReport().size());
        assertEquals("report-spec-001",
                result.getOadrReport().get(0).getReportSpecifierID());
    }

    @Test
    void registerReport_unmarshalFromFile_validPayload()
            throws Oadr20bUnmarshalException {

        File file = new File(EIREPORT_PATH + "oadrRegisterReport.xml");
        assumeFileExists(file);

        OadrRegisterReportType result =
                jaxbContext.unmarshal(file, OadrRegisterReportType.class);

        assertNotNull(result);
        assertEquals("VEN_123", result.getVenID());
        assertFalse(result.getOadrReport().isEmpty());

        OadrReportType report = result.getOadrReport().get(0);
        assertEquals("RS_12345", report.getReportSpecifierID());
        assertFalse(report.getOadrReportDescription().isEmpty());

        OadrReportDescriptionType desc =
                report.getOadrReportDescription().get(0);
        assertEquals("123", desc.getRID());
        assertEquals("usage", desc.getReportType());
        assertEquals("Direct Read", desc.getReadingType());
    }

    @Test
    void registeredReport_unmarshalFromFile_validResponse()
            throws Oadr20bUnmarshalException {

        File file = new File(EIREPORT_PATH + "oadrRegisteredReport.xml");
        assumeFileExists(file);

        OadrRegisteredReportType result =
                jaxbContext.unmarshal(file, OadrRegisteredReportType.class);

        assertNotNull(result);
        assertEquals("200",
                result.getEiResponse().getResponseCode());
    }

    @Test
    void registerReport_telemetryUsage_conformanceRule()
            throws Oadr20bMarshalException {

        OadrReportType oadrReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        "spec-001",
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        System.currentTimeMillis()
                )
                .build();

        OadrRegisterReportType payload = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("req-001", "ven-001")
                .addOadrReport(oadrReport)
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        // Conformance: reportName TELEMETRY_USAGE для EV метрик
        assertTrue(xml.contains("TELEMETRY_USAGE"),
                "Report must use TELEMETRY_USAGE name");
        // Conformance: venID must be present
        assertTrue(xml.contains("ven-001"),
                "oadrRegisterReport must contain venID");
    }

    @Test
    void metadataReport_telemetryUsage_marshalUnmarshal()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrReportDescriptionType powerDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        "ev-chargers_power",
                        ReportEnumeratedType.READING,
                        ReadingTypeEnumeratedType.DIRECT_READ)
                .withPowerRealBase(PowerRealUnitType.WATT, SiScaleCodeType.KILO,
                        BigDecimal.valueOf(50.0), BigDecimal.valueOf(230.0), true)
                .withOadrSamplingRate("PT10S", "PT60S", false)
                .build();

        OadrReportType metadataReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        "meta-spec-001",
                        ReportNameEnumeratedType.METADATA_TELEMETRY_USAGE,
                        System.currentTimeMillis())
                .addReportDescription(powerDescriptor)
                .build();

        OadrRegisterReportType payload = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("req-meta-001", "ven-001")
                .addOadrReport(metadataReport)
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        assertTrue(xml.contains("METADATA_TELEMETRY_USAGE"));
        assertTrue(xml.contains("ven-001"));
        assertTrue(xml.contains("meta-spec-001"));

        OadrRegisterReportType result = (OadrRegisterReportType) jaxbContext.unmarshal(xml);
        assertEquals(1, result.getOadrReport().size());
        assertEquals("METADATA_TELEMETRY_USAGE",
                result.getOadrReport().get(0).getReportName());
    }

    @Test
    void metadataReport_telemetryStatus_marshalUnmarshal()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrReportType statusReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        "meta-status-001",
                        ReportNameEnumeratedType.METADATA_TELEMETRY_STATUS,
                        System.currentTimeMillis())
                .build();

        OadrRegisterReportType payload = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("req-meta-002", "ven-001")
                .addOadrReport(statusReport)
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        assertTrue(xml.contains("METADATA_TELEMETRY_STATUS"));
        assertTrue(xml.contains("meta-status-001"));

        OadrRegisterReportType result = (OadrRegisterReportType) jaxbContext.unmarshal(xml);
        assertEquals("METADATA_TELEMETRY_STATUS",
                result.getOadrReport().get(0).getReportName());
    }

    @Test
    void createReport_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        var powerReal = Oadr20bFactory.createPowerRealType(
                PowerRealUnitType.WATT, SiScaleCodeType.KILO,
                BigDecimal.valueOf(50.0), BigDecimal.valueOf(230.0), true);

        var reportRequest = Oadr20bEiReportBuilders
                .newOadr20bReportRequestTypeBuilder(
                        "rr-001", "spec-001", "PT60S", "PT3600S")
                .withWsCalendarIntervalType(
                        Oadr20bFactory.createWsCalendarIntervalType(System.currentTimeMillis(), "PT3600S"))
                .addSpecifierPayload(
                        Oadr20bFactory.createPowerReal(powerReal),
                        ReadingTypeEnumeratedType.DIRECT_READ,
                        "ev-chargers_power")
                .build();

        OadrCreateReportType payload = Oadr20bEiReportBuilders
                .newOadr20bCreateReportBuilder("req-001", "ven-001")
                .addReportRequest(reportRequest)
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        assertTrue(xml.contains("oadrCreateReport"));
        assertTrue(xml.contains("rr-001"));
        assertTrue(xml.contains("spec-001"));

        OadrCreateReportType result = (OadrCreateReportType) jaxbContext.unmarshal(xml);
        assertEquals("ven-001", result.getVenID());
        assertEquals(1, result.getOadrReportRequest().size());
        assertEquals("rr-001", result.getOadrReportRequest().get(0).getReportRequestID());
    }

    @Test
    void createdReport_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCreatedReportType payload = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder("req-001", 200, "ven-001")
                .addPendingReportRequestId("rr-001")
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        assertTrue(xml.contains("oadrCreatedReport"));
        assertTrue(xml.contains("200"));
        assertTrue(xml.contains("rr-001"));

        OadrCreatedReportType result = (OadrCreatedReportType) jaxbContext.unmarshal(xml);
        assertEquals("200", result.getEiResponse().getResponseCode());
        assertTrue(result.getOadrPendingReports().getReportRequestID().contains("rr-001"));
    }

    private void assumeFileExists(File file) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                file.exists(),
                "Test XML file not found: " + file.getPath()
        );
    }
}
