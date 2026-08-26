package com.qcharge.openadr.service.transport.xml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.dom.DOMResult;
import javax.xml.validation.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;

@Component
@RequiredArgsConstructor
public class OpenAdrXmlCodec {

	private final Schema schema;
	private final JAXBContext jaxbContext;

	public Object unmarshal(String payload) throws Oadr20bUnmarshalException {
		return this.unmarshal(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), Object.class);
	}

	public <T> T unmarshal(String payload, Class<T> responseKlass) throws Oadr20bUnmarshalException {
		return this.unmarshal(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), responseKlass);
	}

	public <T> T unmarshal(InputStream payload, Class<T> responseKlass) throws Oadr20bUnmarshalException {
		Object unmarshal;
		try {
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			unmarshaller.setSchema(schema);
			XMLInputFactory inputFactory = XMLInputFactory.newFactory();
			inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
			inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

			XMLStreamReader reader = inputFactory.createXMLStreamReader(payload);
			try {
				unmarshal = unmarshaller.unmarshal(reader);
			} finally {
				reader.close();
			}
		} catch (JAXBException | XMLStreamException e) {

			throw new Oadr20bUnmarshalException(e);
		}
		try {
			if (unmarshal instanceof JAXBElement) {
				JAXBElement<?> cast = JAXBElement.class.cast(unmarshal);
				return responseKlass.cast(cast.getValue());
			} else {
				return responseKlass.cast(unmarshal);
			}
		} catch (ClassCastException e) {
			throw new Oadr20bUnmarshalException(e);
		}
	}

	public <T> T unmarshal(File payload, Class<T> responseKlass) throws Oadr20bUnmarshalException {
		try (InputStream input = new FileInputStream(payload)) {
			return this.unmarshal(input, responseKlass);
		} catch (IOException e) {
			throw new Oadr20bUnmarshalException(e);
		}
	}

	public <T extends JAXBElement<?>> String marshal(T payload) throws Oadr20bMarshalException {
		StringWriter writer = new StringWriter();
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setSchema(schema);
			marshaller.marshal(payload, writer);
		} catch (JAXBException e) {
			throw new Oadr20bMarshalException(e);
		}
		return writer.toString();
	}

	public void marshal(Object payload, File file) throws Oadr20bMarshalException {
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setSchema(schema);
			marshaller.marshal(payload, file);
		} catch (JAXBException e) {
			throw new Oadr20bMarshalException(e);
		}
	}

	public void marshal(Object payload, DOMResult res) throws Oadr20bMarshalException {
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setSchema(schema);
			marshaller.marshal(payload, res);
		} catch (JAXBException e) {
			throw new Oadr20bMarshalException(e);
		}
	}

	public String marshalRoot(Object payload) throws Oadr20bMarshalException {
		JAXBElement<?> el = null;

		if (payload instanceof OadrDistributeEventType) {
			OadrDistributeEventType value = (OadrDistributeEventType) payload;
			el = Oadr20bFactory.createOadrDistributeEvent(value);

		} else if (payload instanceof OadrCreatedEventType) {
			OadrCreatedEventType value = (OadrCreatedEventType) payload;
			el = Oadr20bFactory.createOadrCreatedEvent(value);

		} else if (payload instanceof OadrRequestEventType) {
			OadrRequestEventType value = (OadrRequestEventType) payload;
			el = Oadr20bFactory.createOadrRequestEvent(value);

		} else if (payload instanceof OadrResponseType) {
			OadrResponseType value = (OadrResponseType) payload;
			el = Oadr20bFactory.createOadrResponse(value);

		} else if (payload instanceof OadrCancelOptType) {
			OadrCancelOptType value = (OadrCancelOptType) payload;
			el = Oadr20bFactory.createOadrCancelOpt(value);

		} else if (payload instanceof OadrCanceledOptType) {
			OadrCanceledOptType value = (OadrCanceledOptType) payload;
			el = Oadr20bFactory.createOadrCanceledOpt(value);

		} else if (payload instanceof OadrCreateOptType) {
			OadrCreateOptType value = (OadrCreateOptType) payload;
			el = Oadr20bFactory.createOadrCreateOpt(value);

		} else if (payload instanceof OadrCreatedOptType) {
			OadrCreatedOptType value = (OadrCreatedOptType) payload;
			el = Oadr20bFactory.createOadrCreatedOpt(value);

		} else if (payload instanceof OadrCancelReportType) {
			OadrCancelReportType value = (OadrCancelReportType) payload;
			el = Oadr20bFactory.createOadrCancelReport(value);

		} else if (payload instanceof OadrCanceledReportType) {
			OadrCanceledReportType value = (OadrCanceledReportType) payload;
			el = Oadr20bFactory.createOadrCanceledReport(value);

		} else if (payload instanceof OadrCreateReportType) {
			OadrCreateReportType value = (OadrCreateReportType) payload;
			el = Oadr20bFactory.createOadrCreateReport(value);

		} else if (payload instanceof OadrCreatedReportType) {
			OadrCreatedReportType value = (OadrCreatedReportType) payload;
			el = Oadr20bFactory.createOadrCreatedReport(value);

		} else if (payload instanceof OadrRegisterReportType) {
			OadrRegisterReportType value = (OadrRegisterReportType) payload;
			el = Oadr20bFactory.createOadrRegisterReport(value);

		} else if (payload instanceof OadrRegisteredReportType) {
			OadrRegisteredReportType value = (OadrRegisteredReportType) payload;
			el = Oadr20bFactory.createOadrRegisteredReport(value);

		} else if (payload instanceof OadrUpdateReportType) {
			OadrUpdateReportType value = (OadrUpdateReportType) payload;
			el = Oadr20bFactory.createOadrUpdateReport(value);

		} else if (payload instanceof OadrUpdatedReportType) {
			OadrUpdatedReportType value = (OadrUpdatedReportType) payload;
			el = Oadr20bFactory.createOadrUpdatedReport(value);

		} else if (payload instanceof OadrCancelPartyRegistrationType) {
			OadrCancelPartyRegistrationType value = (OadrCancelPartyRegistrationType) payload;
			el = Oadr20bFactory.createOadrCancelPartyRegistration(value);

		} else if (payload instanceof OadrCanceledPartyRegistrationType) {
			OadrCanceledPartyRegistrationType value = (OadrCanceledPartyRegistrationType) payload;
			el = Oadr20bFactory.createOadrCanceledPartyRegistration(value);

		} else if (payload instanceof OadrCreatePartyRegistrationType) {
			OadrCreatePartyRegistrationType value = (OadrCreatePartyRegistrationType) payload;
			el = Oadr20bFactory.createOadrCreatePartyRegistration(value);

		} else if (payload instanceof OadrCreatedPartyRegistrationType) {
			OadrCreatedPartyRegistrationType value = (OadrCreatedPartyRegistrationType) payload;
			el = Oadr20bFactory.createOadrCreatedPartyRegistration(value);

		} else if (payload instanceof OadrRequestReregistrationType) {
			OadrRequestReregistrationType value = (OadrRequestReregistrationType) payload;
			el = Oadr20bFactory.createOadrRequestReregistration(value);

		} else if (payload instanceof OadrQueryRegistrationType) {
			OadrQueryRegistrationType value = (OadrQueryRegistrationType) payload;
			el = Oadr20bFactory.createOadrQueryRegistration(value);

		} else if (payload instanceof OadrPollType) {
			OadrPollType value = (OadrPollType) payload;
			el = Oadr20bFactory.createOadrPoll(value);

		} else {
			throw new Oadr20bMarshalException("payload have to be an Oadr20b root element");
		}

		return this.marshal(el);
	}
}
