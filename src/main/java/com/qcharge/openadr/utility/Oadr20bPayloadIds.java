package com.qcharge.openadr.utility;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;

public final class Oadr20bPayloadIds {

    private Oadr20bPayloadIds() {
    }

    public static String venIdOf(Object payload) {
        return switch (payload) {
            case OadrCreatedPartyRegistrationType p -> p.getVenID();
            case OadrResponseType p -> p.getVenID();
            case OadrRegisteredReportType p -> p.getVenID();
            case OadrUpdatedReportType p -> p.getVenID();
            case OadrCanceledReportType p -> p.getVenID();
            case OadrCanceledPartyRegistrationType p -> p.getVenID();

            case OadrCreateReportType p -> p.getVenID();
            case OadrCancelReportType p -> p.getVenID();
            case OadrUpdateReportType p -> p.getVenID();
            case OadrCancelPartyRegistrationType p -> p.getVenID();
            case OadrRequestReregistrationType p -> p.getVenID();

            default -> null;
        };
    }

    public static String vtnIdOf(Object payload) {
        return switch (payload) {
            case OadrCreatedPartyRegistrationType p -> p.getVtnID();
            case OadrDistributeEventType p -> p.getVtnID();
            default -> null;
        };
    }
}