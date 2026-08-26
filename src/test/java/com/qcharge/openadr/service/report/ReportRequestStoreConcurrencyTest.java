package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.repository.ReportRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:report-concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=create-drop",
        "db.address=localhost",
        "openadr.ven.id=VEN-TEST",
        "openadr.ven.name=VEN-TEST",
        "openadr.vtn.url=http://localhost",
        "openadr.vtn.id=VTN-TEST",
        "openadr.keystore.password=test",
        "openadr.truststore.password=test",
        "openadr.security.keystore-password=test",
        "openadr.security.truststore-password=test",
        "aws.s3.static.url=http://localhost"
})
@ContextConfiguration(classes = ReportRequestStoreConcurrencyTest.Config.class)
@Import(ReportRequestStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReportRequestStoreConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Autowired ReportRequestStore store;
    @Autowired ReportRequestRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void concurrentSchedulersClaimDueReportOnlyOnce() throws Exception {
        repository.saveAndFlush(activeRequest("DUE", NOW));
        int workerCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);

        try {
            List<CompletableFuture<Optional<ReportRequestStore.DeliveryClaim>>> attempts =
                    new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                attempts.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return store.claimDue("DUE", NOW);
                }, workers));
            }

            start.countDown();
            long successfulClaims = attempts.stream()
                    .map(future -> future.orTimeout(5, TimeUnit.SECONDS).join())
                    .filter(Optional::isPresent)
                    .count();

            assertThat(successfulClaims).isEqualTo(1);
            ReportRequest persisted = repository.findByReportRequestId("DUE")
                    .orElseThrow();
            assertThat(persisted.getDeliveryState())
                    .isEqualTo(ReportRequest.DeliveryState.IN_PROGRESS);
            assertThat(persisted.getDeliveryToken()).isNotBlank();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancellationRacingDeliveryCannotBeLostOrResurrected() throws Exception {
        repository.saveAndFlush(activeRequest("RACE", NOW));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<Optional<ReportRequestStore.DeliveryClaim>> delivery =
                    CompletableFuture.supplyAsync(() -> {
                        await(start);
                        return store.claimDue("RACE", NOW);
                    }, workers);
            CompletableFuture<ReportRequestStore.CancellationBatch> cancellation =
                    CompletableFuture.supplyAsync(() -> {
                        await(start);
                        return store.beginCancellation(List.of("RACE"), true);
                    }, workers);

            start.countDown();
            Optional<ReportRequestStore.DeliveryClaim> claim = delivery
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            assertThat(cancellation.orTimeout(5, TimeUnit.SECONDS).join().accepted())
                    .isTrue();

            ReportRequest afterRace = repository.findByReportRequestId("RACE")
                    .orElseThrow();
            assertThat(afterRace.getStatus())
                    .isEqualTo(ReportRequest.Status.FINAL_REPORT_PENDING);

            if (claim.isPresent()) {
                assertThat(afterRace.getDeliveryState())
                        .isEqualTo(ReportRequest.DeliveryState.IN_PROGRESS);
                assertThat(store.claimFinal("RACE", NOW)).isEmpty();
                assertThat(store.recordDelivery(
                        claim.orElseThrow(),
                        NOW,
                        Optional.of(NOW.plusSeconds(60))
                )).isTrue();
                ReportRequest finalPending = repository.findByReportRequestId("RACE")
                        .orElseThrow();
                assertThat(finalPending.getStatus())
                        .isEqualTo(ReportRequest.Status.FINAL_REPORT_PENDING);
                assertThat(finalPending.getLastReportedAt()).isEqualTo(NOW);
            }

            ReportRequestStore.DeliveryClaim finalClaim = store
                    .claimFinal("RACE", NOW.plusSeconds(1))
                    .orElseThrow();
            assertThat(store.completeFinalCancellation(finalClaim)).isTrue();

            ReportRequest completed = repository.findByReportRequestId("RACE")
                    .orElseThrow();
            assertThat(completed.getStatus()).isEqualTo(ReportRequest.Status.CANCELLED);
            assertThat(completed.getDeliveryState()).isEqualTo(ReportRequest.DeliveryState.IDLE);
            assertThat(completed.getNextReportAt()).isNull();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleOwnerCannotCompleteIt() {
        ReportRequest request = activeRequest("STALE", NOW);
        request.setDeliveryState(ReportRequest.DeliveryState.IN_PROGRESS);
        request.setDeliveryToken("STALE-TOKEN");
        request.setDeliveryClaimedAt(NOW.minusSeconds(121));
        repository.saveAndFlush(request);
        var staleClaim = new ReportRequestStore.DeliveryClaim(request, "STALE-TOKEN");

        ReportRequestStore.DeliveryClaim replacement = store
                .claimDue("STALE", NOW)
                .orElseThrow();

        assertThat(replacement.token()).isNotEqualTo("STALE-TOKEN");
        assertThat(store.recordDelivery(
                staleClaim,
                NOW,
                Optional.of(NOW.plusSeconds(60))
        )).isFalse();
        assertThat(store.recordDelivery(
                replacement,
                NOW,
                Optional.of(NOW.plusSeconds(60))
        )).isTrue();

        ReportRequest persisted = repository.findByReportRequestId("STALE")
                .orElseThrow();
        assertThat(persisted.getDeliveryState()).isEqualTo(ReportRequest.DeliveryState.IDLE);
        assertThat(persisted.getLastReportedAt()).isEqualTo(NOW);
        assertThat(persisted.getNextReportAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private ReportRequest activeRequest(String requestId, Instant dueAt) {
        ReportRequest request = new ReportRequest();
        request.setReportRequestId(requestId);
        request.setReportSpecifierId(ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS);
        request.setReportName("TELEMETRY_STATUS");
        request.setRequestedRids(ReportService.RID_RESOURCE_STATUS);
        request.setGranularitySeconds(60);
        request.setReportBackDurationSeconds(60);
        request.setRequestedStart(NOW.minusSeconds(60));
        request.setRequestedDurationSeconds(0L);
        request.setNextReportAt(dueAt);
        request.setStatus(ReportRequest.Status.ACTIVE);
        request.setDeliveryState(ReportRequest.DeliveryState.IDLE);
        return request;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent test", exception);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ReportRequest.class)
    @EnableJpaRepositories(basePackageClasses = ReportRequestRepository.class)
    static class Config {

        @Bean
        OpenAdrProperties openAdrProperties() {
            OpenAdrProperties properties = new OpenAdrProperties();
            properties.getReport().setDeliveryLeaseSeconds(120);
            return properties;
        }
    }
}
