package com.qcharge.openadr.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.config.OpenAdrXmlConfiguration;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.service.transport.OpenAdrHttpStatusPolicy;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorPolicy;
import com.qcharge.openadr.service.transport.OpenAdrApplicationResponseEvaluator;
import com.qcharge.openadr.service.transport.RetryHandler;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.transport.xml.OpenAdrXmlCodec;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.validation.OpenAdrExchangeValidationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class VtnTransportServiceValidateIdsTest {

    private static OpenAdrXmlCodec jaxbContext;

    @BeforeAll
    static void initJaxb() throws Exception {
        OpenAdrXmlConfiguration configuration = new OpenAdrXmlConfiguration();
        jaxbContext = new OpenAdrXmlCodec(
                configuration.openAdrSchema(),
                configuration.openAdrJaxbContext()
        );
    }

    @Mock RestClient restClient;
    @Mock RetryHandler retryHandler;
    @Mock OpenAdrProperties properties;
    @Mock OpenAdrProperties.Vtn vtnProps;
    @Mock OpenAdrExchangeValidationService exchangeValidationService;
    @Mock OpenAdrSessionProvider sessionProvider;

    VtnTransportService service;

    @BeforeEach
    void setUp() {
        when(properties.getVtn()).thenReturn(vtnProps);
        when(vtnProps.getId()).thenReturn(null); // skip vtnId validation
        service = new VtnTransportService(
                restClient,
                properties,
                retryHandler,
                new OpenAdrHttpStatusPolicy(),
                exchangeValidationService,
                new OpenAdrApplicationResponseEvaluator(
                        new OpenAdrApplicationErrorPolicy()
                ),
                new OpenAdrApplicationErrorMapper(),
                new OpenAdrReplyFactory(),
                sessionProvider,
                jaxbContext
        );
        when(sessionProvider.current())
                .thenReturn(com.qcharge.openadr.TestSessionFixtures.bootstrapSession());
        clearInvocations(sessionProvider);
    }

    /**
     * oadrCreatedPartyRegistration is the source of truth for venID assignment —
     * validateIds() must NOT check it against currentVenId().
     */
    @Test
    void send_doesNotThrow_whenCreatedPartyRegistrationHasDifferentVenId() throws Exception {
        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("req-001", OpenADRResponseCode.OK)
                .build();

        OadrCreatedPartyRegistrationType created = registrationResponse(eiResponse, "VEN_DIFFERENT");

        String responseXml = jaxbContext.marshalRoot(created);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        Object result = assertDoesNotThrow(
                () -> service.send(OpenAdrOperations.QUERY_REGISTRATION, buildOutgoingPayload())
        );

        assertInstanceOf(OadrCreatedPartyRegistrationType.class, result);
    }

    @Test
    void send_throwsApplicationException_whenVtnReturnsNotRegistered() throws Exception {
        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(
                        "req-463",
                        OpenADRResponseCode.NOT_REGISTERED
                )
                .withDescription("Not Registered/Authorized")
                .build();

        OadrCreatedPartyRegistrationType created = registrationResponse(eiResponse, "TH_VEN");

        String responseXml = jaxbContext.marshalRoot(created);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> service.send(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        buildOutgoingPayload()
                )
        );

        assertEquals(OpenADRResponseCode.NOT_REGISTERED, exception.getResponseCode());
        assertEquals("Not Registered/Authorized", exception.getResponseDescription());
        assertEquals("req-463", exception.getRequestId());
        assertEquals("queryRegistration", exception.getOperationName());
        assertEquals(
                com.qcharge.openadr.service.transport.ApplicationErrorAction
                        .REQUIRE_REREGISTRATION,
                exception.getAction()
        );
    }

    @Test
    void send_throwsApplicationException_whenResponseTypeDoesNotMatchOperation() throws Exception {
        OadrRegisteredReportType registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        "req-001",
                        OpenADRResponseCode.OK,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> service.send(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        buildOutgoingPayload()
                )
        );

        assertTrue(exception.getMessage().contains("queryRegistration"));
        assertTrue(exception.getMessage().contains("OadrCreatedPartyRegistrationType"));
        assertTrue(exception.getMessage().contains("OadrRegisteredReportType"));
        assertEquals(
                OpenADRResponseCode.COMPLIANCE_ERROR_OTHER,
                exception.getResponseCode()
        );
    }

    @Test
    void send_preservesApplicationErrorBeforeResponseTypeValidation() throws Exception {
        OadrRegisteredReportType registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        "req-452",
                        OpenADRResponseCode.INVALID_ID,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> service.send(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        buildOutgoingPayload()
                )
        );

        assertEquals(OpenADRResponseCode.INVALID_ID, exception.getResponseCode());
        assertEquals("req-452", exception.getRequestId());
        assertEquals("queryRegistration", exception.getOperationName());
    }

    @Test
    void send_logsRawVtnResponse_forRequestEvent(CapturedOutput output) throws Exception {
        OadrResponseType response = Oadr20bResponseBuilders
                .newOadr20bResponseBuilder(
                        "request-event-001",
                        OpenADRResponseCode.OK,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(response);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        service.send(OpenAdrOperations.REQUEST_EVENT, buildRequestEventPayload());

        assertTrue(output.getOut().contains(
                "Raw VTN event response. operation=requestEvent"
        ));
        assertTrue(output.getOut().contains(responseXml));
    }

    @Test
    void send_doesNotLogRawVtnEventResponse_forRegistration(CapturedOutput output) throws Exception {
        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("req-001", OpenADRResponseCode.OK)
                .build();

        OadrCreatedPartyRegistrationType created = registrationResponse(eiResponse, "TH_VEN");

        String responseXml = jaxbContext.marshalRoot(created);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        service.send(OpenAdrOperations.QUERY_REGISTRATION, buildOutgoingPayload());

        assertFalse(output.getOut().contains("Raw VTN event response"));
    }

    /**
     * When the response venId matches currentVenId(), no exception is thrown.
     */
    @Test
    void send_doesNotThrow_whenVenIdMatches() throws Exception {
        OadrRegisteredReportType registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        "req-001",
                        OpenADRResponseCode.OK,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrSessionSnapshot capturedSession =
                registeredSession("TH_VEN", "test-vtn", "REG-1");

        assertDoesNotThrow(() -> service.send(
                OpenAdrOperations.REGISTER_REPORT,
                buildReportPayload(),
                capturedSession
        ));

        ArgumentCaptor<OpenAdrExchangeContext<?, ?>> contextCaptor =
                ArgumentCaptor.forClass(OpenAdrExchangeContext.class);
        verify(exchangeValidationService).validate(contextCaptor.capture());
        assertSame(capturedSession, contextCaptor.getValue().session());
        verifyNoInteractions(sessionProvider);
    }

    private com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType buildOutgoingPayload() {
        return Oadr20bEiRegisterPartyBuilders
                .newOadr20bQueryRegistrationBuilder("req-outgoing")
                .build();
    }

    private OadrRegisterReportType buildReportPayload() {
        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("req-outgoing", "TH_VEN")
                .build();
    }

    private OadrRequestEventType buildRequestEventPayload() {
        return Oadr20bEiEventBuilders
                .newOadrRequestEventBuilder("TH_VEN", "request-event-001")
                .build();
    }

    private OadrCreatedPartyRegistrationType registrationResponse(EiResponseType eiResponse, String venId) {
        return Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatedPartyRegistrationBuilder(eiResponse, venId, "test-vtn")
                .addOadrProfile(
                        Oadr20bEiRegisterPartyBuilders
                                .newOadr20bOadrProfileBuilder("2.0b")
                                .addTransport(OadrTransportType.SIMPLE_HTTP)
                                .build()
                )
                .build();
    }
}
