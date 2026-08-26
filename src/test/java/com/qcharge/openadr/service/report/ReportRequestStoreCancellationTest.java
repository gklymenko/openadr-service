package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.repository.ReportRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportRequestStoreCancellationTest {

    private final ReportRequestRepository repository = mock(ReportRequestRepository.class);
    private final ReportRequestStore store = new ReportRequestStore(repository);

    @Test
    void r1_3040_rejectsWholeBatchWithoutMutatingValidRequest() {
        ReportRequest known = activeRequest("KNOWN");
        when(repository.lockAllByReportRequestIdIn(Set.of("KNOWN", "UNKNOWN")))
                .thenReturn(List.of(known));

        ReportRequestStore.CancellationBatch result = store.beginCancellation(
                List.of("KNOWN", "UNKNOWN"),
                false
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.invalidReportRequestIds()).containsExactly("UNKNOWN");
        assertThat(known.getStatus()).isEqualTo(ReportRequest.Status.ACTIVE);
        assertThat(known.getNextReportAt()).isNotNull();
    }

    @Test
    void r1_3045_atomicallyMovesWholeBatchToFinalReportPending() {
        ReportRequest first = activeRequest("FIRST");
        ReportRequest second = activeRequest("SECOND");
        when(repository.lockAllByReportRequestIdIn(Set.of("FIRST", "SECOND")))
                .thenReturn(List.of(first, second));

        ReportRequestStore.CancellationBatch result = store.beginCancellation(
                List.of("FIRST", "SECOND"),
                true
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.requests()).containsExactly(first, second);
        assertThat(List.of(first, second)).allSatisfy(request -> {
            assertThat(request.getStatus())
                    .isEqualTo(ReportRequest.Status.FINAL_REPORT_PENDING);
            assertThat(request.getNextReportAt()).isNull();
        });
        verify(repository).lockAllByReportRequestIdIn(Set.of("FIRST", "SECOND"));
    }

    private ReportRequest activeRequest(String requestId) {
        ReportRequest request = new ReportRequest();
        request.setReportRequestId(requestId);
        request.setStatus(ReportRequest.Status.ACTIVE);
        request.setNextReportAt(Instant.parse("2026-08-26T10:05:00Z"));
        return request;
    }
}
