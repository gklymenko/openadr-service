package com.qcharge.openadr.model.oadr20b.builders.eireport;

import java.util.List;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;

public class Oadr20bUpdateReportBuilder {
    private OadrUpdateReportType oadrUpdateReportType;

    public Oadr20bUpdateReportBuilder(String requestId, String venId) {
        oadrUpdateReportType = Oadr20bFactory.createOadrUpdateReportType(requestId, venId);
    }

    public Oadr20bUpdateReportBuilder addReport(List<OadrReportType> report) {
        oadrUpdateReportType.getOadrReport().addAll(report);
        return this;
    }

    public Oadr20bUpdateReportBuilder addReport(OadrReportType report) {
        oadrUpdateReportType.getOadrReport().add(report);
        return this;
    }

    public Oadr20bUpdateReportBuilder withSchemaVersion(String schemaVersion) {
        oadrUpdateReportType.setSchemaVersion(schemaVersion);
        return this;
    }

    public OadrUpdateReportType build() {
        return oadrUpdateReportType;
    }
}
