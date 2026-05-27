package com.qcharge.openadr.model.oadr20b.builders.eireport;

import jakarta.xml.bind.JAXBElement;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.emix.ItemBaseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.xcal.WsCalendarIntervalType;

public class Oadr20bReportRequestTypeBuilder {

	private OadrReportRequestType oadrReportRequestType;

	public Oadr20bReportRequestTypeBuilder(String reportRequestId, String reportSpecifierId, String granularity,
			String reportBackDuration) {
		oadrReportRequestType = Oadr20bFactory.createOadrReportRequestType(reportRequestId, reportSpecifierId,
				granularity, reportBackDuration);
	}

	public Oadr20bReportRequestTypeBuilder withWsCalendarIntervalType(WsCalendarIntervalType calendar) {
		oadrReportRequestType.getReportSpecifier().setReportInterval(calendar);
		return this;
	}

	public Oadr20bReportRequestTypeBuilder addSpecifierPayload(JAXBElement<? extends ItemBaseType> baseItem,
			ReadingTypeEnumeratedType readingTypeEnumeratedType, String rid) {
		SpecifierPayloadType specifierPayload = Oadr20bFactory.createSpecifierPayloadType(baseItem,
				readingTypeEnumeratedType, rid);
		oadrReportRequestType.getReportSpecifier().getSpecifierPayload().add(specifierPayload);
		return this;
	}

	public OadrReportRequestType build() {
		return oadrReportRequestType;
	}
}
