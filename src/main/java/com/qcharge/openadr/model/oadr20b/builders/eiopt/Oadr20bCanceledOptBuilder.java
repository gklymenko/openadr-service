package com.qcharge.openadr.model.oadr20b.builders.eiopt;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;

public class Oadr20bCanceledOptBuilder {

    private OadrCanceledOptType oadrCanceledOpt;

    public Oadr20bCanceledOptBuilder(String requestId, int responseCode, String optId) {
        oadrCanceledOpt = Oadr20bFactory.createOadrCanceledOptType(requestId, responseCode, optId);
    }

    public Oadr20bCanceledOptBuilder withSchemaVersion(String schemaVersion) {
        oadrCanceledOpt.setSchemaVersion(schemaVersion);
        return this;
    }

    public OadrCanceledOptType build() {
        return oadrCanceledOpt;
    }

}
