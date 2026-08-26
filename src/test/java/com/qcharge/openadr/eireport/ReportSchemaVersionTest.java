package com.qcharge.openadr.eireport;

import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSchemaVersionTest {

    @Test
    void allTopLevelReportPayloadsUse20bSchemaVersion() {
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bCancelReportBuilder("request", "ven", false)
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bCanceledReportBuilder("request", 200, "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bCreateReportBuilder("request", "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder("request", 200, "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("request", "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder("request", 200, "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bUpdateReportBuilder("request", "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
        assertThat(Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder("request", 200, "ven")
                .build()
                .getSchemaVersion()).isEqualTo("2.0b");
    }
}
