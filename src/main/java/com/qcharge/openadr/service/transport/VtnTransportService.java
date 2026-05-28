package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayload;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class VtnTransportService {
    private final RestClient restClient;
    private final OpenAdrProperties properties;

    public Object send(String endpoint, Object payload) {
        try {
            Oadr20bJAXBContext jaxb = Oadr20bJAXBContext.getInstance();

            String xmlPayload = jaxb.marshalRoot(payload);
            log.debug("Sending to {}: {}", endpoint, xmlPayload);

            String xmlResponse = restClient.post()
                    .uri(properties.getVtn().getUrl() + endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                    .body(xmlPayload)
                    .retrieve()
                    .body(String.class);

            log.debug("Received from {}: {}", endpoint, xmlResponse);

            Object response = jaxb.unmarshal(xmlResponse);

            if (response instanceof OadrPayload oadrPayload) {
                return unwrapOadrPayload(oadrPayload);
            }

            validateIds(response);

            return response;

        } catch (JAXBException e) {
            throw new RuntimeException("Failed to initialize JAXBContext", e);
        } catch (Oadr20bMarshalException e) {
            throw new RuntimeException("Failed to marshal payload to XML", e);
        } catch (Oadr20bUnmarshalException e) {
            throw new RuntimeException("Failed to unmarshal VTN response", e);
        }
    }

    private Object unwrapOadrPayload(OadrPayload oadrPayload) {
        var signed = oadrPayload.getOadrSignedObject();
        if (signed == null) return oadrPayload;

        if (signed.getOadrResponse() != null)
            return signed.getOadrResponse();
        if (signed.getOadrDistributeEvent() != null)
            return signed.getOadrDistributeEvent();
        if (signed.getOadrCreatedPartyRegistration() != null)
            return signed.getOadrCreatedPartyRegistration();
        if (signed.getOadrRegisteredReport() != null)
            return signed.getOadrRegisteredReport();
        if (signed.getOadrCreatedEvent() != null)
            return signed.getOadrCreatedEvent();
        if (signed.getOadrCreatedReport() != null)
            return signed.getOadrCreatedReport();
        if (signed.getOadrUpdatedReport() != null)
            return signed.getOadrUpdatedReport();
        if (signed.getOadrCanceledReport() != null)
            return signed.getOadrCanceledReport();
        if (signed.getOadrCanceledPartyRegistration() != null)
            return signed.getOadrCanceledPartyRegistration();

        return oadrPayload;
    }

    // Rule 21: validate venID and vtnID in every received payload
    private void validateIds(Object response) {
        String expectedVenId = properties.getVen().getId();
        String expectedVtnId = properties.getVtn().getId();

        String receivedVenId = extractVenId(response);
        String receivedVtnId = extractVtnId(response);

        if (receivedVenId != null && !receivedVenId.isBlank() && !expectedVenId.equals(receivedVenId)) {
            log.warn("venID mismatch: expected={}, received={}", expectedVenId, receivedVenId);
        }

        if (receivedVtnId != null && !receivedVtnId.isBlank() && !expectedVtnId.equals(receivedVtnId)) {
            log.warn("vtnID mismatch: expected={}, received={}", expectedVtnId, receivedVtnId);
        }
    }

    private String extractVenId(Object response) {
        if (response instanceof OadrCreatedPartyRegistrationType r)
            return r.getVenID();
        if (response instanceof OadrDistributeEventType)
            return null;
        if (response instanceof OadrResponseType r)
            return r.getVenID();
        if (response instanceof OadrRegisteredReportType r)
            return r.getVenID();
        return null;
    }

    private String extractVtnId(Object response) {
        if (response instanceof OadrCreatedPartyRegistrationType r)
            return r.getVtnID();
        if (response instanceof OadrDistributeEventType r)
            return r.getVtnID();
        return null;
    }
}
