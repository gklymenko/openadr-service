package com.qcharge.openadr.model.oadr20b.builders;

import com.qcharge.openadr.model.oadr20b.builders.poll.Oadr20bPollBuilder;
import com.qcharge.openadr.model.oadr20b.ei.SchemaVersionEnumeratedType;

public class Oadr20bPollBuilders {

    private Oadr20bPollBuilders() {
    }

    public static Oadr20bPollBuilder newOadr20bPollBuilder(String venId) {
        return new Oadr20bPollBuilder(venId)
                .withSchemaVersion(SchemaVersionEnumeratedType.OADR_20B.value());
    }
}
