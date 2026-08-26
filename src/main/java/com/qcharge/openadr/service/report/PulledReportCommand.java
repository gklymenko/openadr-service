package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;

public sealed interface PulledReportCommand permits
        PulledReportCommand.Create,
        PulledReportCommand.Register,
        PulledReportCommand.Cancel,
        PulledReportCommand.Update {

    Object payload();

    OpenAdrSessionSnapshot session();

    static PulledReportCommand create(
            OadrCreateReportType payload,
            OpenAdrSessionSnapshot session
    ) {
        return new Create(payload, session);
    }

    static PulledReportCommand register(
            OadrRegisterReportType payload,
            OpenAdrSessionSnapshot session
    ) {
        return new Register(payload, session);
    }

    static PulledReportCommand cancel(
            OadrCancelReportType payload,
            OpenAdrSessionSnapshot session
    ) {
        return new Cancel(payload, session);
    }

    static PulledReportCommand update(
            OadrUpdateReportType payload,
            OpenAdrSessionSnapshot session
    ) {
        return new Update(payload, session);
    }

    record Create(
            OadrCreateReportType payload,
            OpenAdrSessionSnapshot session
    ) implements PulledReportCommand {
    }

    record Register(
            OadrRegisterReportType payload,
            OpenAdrSessionSnapshot session
    ) implements PulledReportCommand {
    }

    record Cancel(
            OadrCancelReportType payload,
            OpenAdrSessionSnapshot session
    ) implements PulledReportCommand {
    }

    record Update(
            OadrUpdateReportType payload,
            OpenAdrSessionSnapshot session
    ) implements PulledReportCommand {
    }
}
