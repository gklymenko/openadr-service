package com.qcharge.openadr.model.oadr20b.builders.eievent;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;

public class Oadr20bRequestEventBuilder {

    private OadrRequestEventType event;

    public Oadr20bRequestEventBuilder(String venId, String requestId) {
        event = Oadr20bFactory.createOadrRequestEventType(venId, requestId);
    }

    public Oadr20bRequestEventBuilder withReplyLimit(long limit) {
        event.getEiRequestEvent().setReplyLimit(limit);
        return this;
    }

    public Oadr20bRequestEventBuilder withSchemaVersion(String schemaVersion) {
        event.setSchemaVersion(schemaVersion);
        return this;
    }

    public OadrRequestEventType build() {
        return event;
    }

}
