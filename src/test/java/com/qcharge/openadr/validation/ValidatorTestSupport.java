package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrProfiles;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransports;
import com.qcharge.openadr.model.oadr20b.xcal.DurationPropType;

final class ValidatorTestSupport {

    private ValidatorTestSupport() {
    }

    static EiResponseType eiResponse(String requestId) {
        EiResponseType response = new EiResponseType();
        response.setRequestID(requestId);
        response.setResponseCode(String.valueOf(ApplicationLayerErrorCodes.OK));
        return response;
    }

    static OadrCreatedPartyRegistrationType registrationResponse(String requestId) {
        OadrCreatedPartyRegistrationType response = new OadrCreatedPartyRegistrationType();
        response.setEiResponse(eiResponse(requestId));
        response.setVenID("VEN-1");
        response.setVtnID("VTN-1");
        response.setRegistrationID("REG-1");
        response.setOadrProfiles(profiles());

        DurationPropType pollFrequency = new DurationPropType();
        pollFrequency.setDuration("PT10S");
        response.setOadrRequestedOadrPollFreq(pollFrequency);
        return response;
    }

    static OadrProfiles profiles() {
        OadrTransports.OadrTransport transport = new OadrTransports.OadrTransport();
        transport.setOadrTransportName(OadrTransportType.SIMPLE_HTTP);

        OadrTransports transports = new OadrTransports();
        transports.getOadrTransport().add(transport);

        OadrProfiles.OadrProfile profile = new OadrProfiles.OadrProfile();
        profile.setOadrProfileName("2.0b");
        profile.setOadrTransports(transports);

        OadrProfiles profiles = new OadrProfiles();
        profiles.getOadrProfile().add(profile);
        return profiles;
    }
}
