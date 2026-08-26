package com.qcharge.openadr.model.oadr20b.builders.eipayload;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayloadResourceStatusType;

public class Oadr20bReportIntervalTypeBuilder {

	private IntervalType interval;

	public Oadr20bReportIntervalTypeBuilder(String intervalId, Long start, String xmlDuration, String rid,
			Long confidence, Float accuracy, Float value) {

		interval = Oadr20bFactory.createReportIntervalType(intervalId, start, xmlDuration, rid, confidence, accuracy,
				value);
	}

	public Oadr20bReportIntervalTypeBuilder(String intervalId, Long start, String xmlDuration, String rid,
			Long confidence, Float accuracy, OadrPayloadResourceStatusType value) {

		interval = Oadr20bFactory.createReportIntervalType(intervalId, start, xmlDuration, rid, confidence, accuracy,
				value);
	}

	public IntervalType build() {
		return interval;
	}
}
