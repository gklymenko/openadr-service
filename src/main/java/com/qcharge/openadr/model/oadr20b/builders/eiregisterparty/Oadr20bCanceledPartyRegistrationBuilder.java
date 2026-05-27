package com.qcharge.openadr.model.oadr20b.builders.eiregisterparty;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;

public class Oadr20bCanceledPartyRegistrationBuilder {

	private OadrCanceledPartyRegistrationType oadrCanceledPartyRegistration;

	public Oadr20bCanceledPartyRegistrationBuilder(EiResponseType eiresponse, String registrationId, String venId) {
		oadrCanceledPartyRegistration = Oadr20bFactory.createOadrCanceledPartyRegistrationType(eiresponse,
				registrationId, venId);
	}

	public Oadr20bCanceledPartyRegistrationBuilder withSchemaVersion(String schemaVersion) {
		oadrCanceledPartyRegistration.setSchemaVersion(schemaVersion);
		return this;
	}

	public OadrCanceledPartyRegistrationType build() {
		return oadrCanceledPartyRegistration;
	}
}
