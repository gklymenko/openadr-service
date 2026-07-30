package com.qcharge.openadr.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayload;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.service.transport.OpenAdrHttpStatusPolicy;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorPolicy;
import com.qcharge.openadr.service.transport.OpenAdrApplicationResponseEvaluator;
import com.qcharge.openadr.service.transport.RetryHandler;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.validation.OpenAdrExchangeValidationService;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import javax.xml.namespace.QName;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VtnTransportServiceValidateIdsTest {

    private static Oadr20bJAXBContext jaxbContext;

    @BeforeAll
    static void initJaxb() throws JAXBException {
        jaxbContext = Oadr20bJAXBContext.getInstance();
    }

    @Mock RestClient restClient;
    @Mock RetryHandler retryHandler;
    @Mock OpenAdrProperties properties;
    @Mock OpenAdrProperties.Xml xmlProps;
    @Mock OpenAdrProperties.Vtn vtnProps;
    @Mock OpenAdrExchangeValidationService exchangeValidationService;
    @Mock OpenAdrSessionProvider sessionProvider;

    VtnTransportService service;

    @BeforeEach
    void setUp() {
        when(properties.getXml()).thenReturn(xmlProps);
        when(xmlProps.isValidate()).thenReturn(false);
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
                sessionProvider
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
                .newOadr20bEiResponseBuilder("req-001", ApplicationLayerErrorCodes.OK)
                .build();

        OadrCreatedPartyRegistrationType created = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatedPartyRegistrationBuilder(eiResponse, "VEN_DIFFERENT", "test-vtn")
                .build();

        String responseXml = jaxbContext.marshalRoot(created, false);
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
                        ApplicationLayerErrorCodes.NOT_REGISTERED
                )
                .withDescription("Not Registered/Authorized")
                .build();

        OadrCreatedPartyRegistrationType created = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatedPartyRegistrationBuilder(eiResponse, "TH_VEN", "test-vtn")
                .build();

        String responseXml = jaxbContext.marshalRoot(created, false);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> service.send(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        buildOutgoingPayload()
                )
        );

        assertEquals(ApplicationLayerErrorCodes.NOT_REGISTERED, exception.getResponseCode());
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
                        ApplicationLayerErrorCodes.OK,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport, false);
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
                ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                exception.getResponseCode()
        );
    }

    @Test
    void send_preservesApplicationErrorBeforeResponseTypeValidation() throws Exception {
        OadrRegisteredReportType registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        "req-452",
                        ApplicationLayerErrorCodes.INVALID_ID,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport, false);
        doReturn(responseXml).when(retryHandler).executeWithRetry(any(), any());

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> service.send(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        buildOutgoingPayload()
                )
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
        assertEquals("req-452", exception.getRequestId());
        assertEquals("queryRegistration", exception.getOperationName());
    }

    /**
     * When the response venId matches currentVenId(), no exception is thrown.
     */
    @Test
    void send_doesNotThrow_whenVenIdMatches() throws Exception {
        OadrRegisteredReportType registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        "req-001",
                        ApplicationLayerErrorCodes.OK,
                        "TH_VEN"
                )
                .build();

        String responseXml = jaxbContext.marshalRoot(registeredReport, false);
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
}
