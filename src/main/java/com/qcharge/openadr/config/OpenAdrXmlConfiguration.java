package com.qcharge.openadr.config;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

@Configuration(proxyBeanMethods = false)
public class OpenAdrXmlConfiguration {

    static final String SCHEMA_DIRECTORY = "openadr/schema/2.0b/20130701/";
    static final String ROOT_SCHEMA = "oadr_20b.xsd";
    static final String SIGNATURE_PROPERTIES_SCHEMA = "oadr_xmldsig-properties-schema.xsd";
    private static final String JAXB_CONTEXT_PATH = String.join(":",
            "com.qcharge.openadr.model.oadr20b.atom",
            "com.qcharge.openadr.model.oadr20b.ei",
            "com.qcharge.openadr.model.oadr20b.emix",
            "com.qcharge.openadr.model.oadr20b.gml",
            "com.qcharge.openadr.model.oadr20b.greenbutton",
            "com.qcharge.openadr.model.oadr20b.iso",
            "com.qcharge.openadr.model.oadr20b.oadr",
            "com.qcharge.openadr.model.oadr20b.power",
            "com.qcharge.openadr.model.oadr20b.pyld",
            "com.qcharge.openadr.model.oadr20b.siscale",
            "com.qcharge.openadr.model.oadr20b.strm",
            "com.qcharge.openadr.model.oadr20b.xcal",
            "com.qcharge.openadr.model.oadr20b.xmldsig",
            "com.qcharge.openadr.model.oadr20b.xmldsig.properties",
            "com.qcharge.openadr.model.oadr20b.xmldsig11"
    );

    @Bean
    public Schema openAdrSchema() throws SAXException, IOException {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver(new ClasspathSchemaResolver());

        ClassPathResource root = schemaResource(ROOT_SCHEMA);
        ClassPathResource signatureProperties = schemaResource(SIGNATURE_PROPERTIES_SCHEMA);
        try (InputStream rootInput = root.getInputStream();
             InputStream signaturePropertiesInput = signatureProperties.getInputStream()) {
            return factory.newSchema(new Source[]{
                    schemaSource(ROOT_SCHEMA, rootInput),
                    schemaSource(SIGNATURE_PROPERTIES_SCHEMA, signaturePropertiesInput)
            });
        }
    }

    @Bean
    public JAXBContext openAdrJaxbContext() throws JAXBException {
        return JAXBContext.newInstance(
                JAXB_CONTEXT_PATH,
                OpenAdrXmlConfiguration.class.getClassLoader()
        );
    }

    private static ClassPathResource schemaResource(String systemId) {
        String fileName = systemId.substring(systemId.lastIndexOf('/') + 1);
        return new ClassPathResource(SCHEMA_DIRECTORY + fileName);
    }

    private static StreamSource schemaSource(String fileName, InputStream input) {
        return new StreamSource(input, "classpath:/" + SCHEMA_DIRECTORY + fileName);
    }

    private static final class ClasspathSchemaResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(
                String type,
                String namespaceUri,
                String publicId,
                String systemId,
                String baseUri
        ) {
            if (systemId == null) {
                return null;
            }

            ClassPathResource resource = schemaResource(systemId);
            try {
                String fileName = systemId.substring(systemId.lastIndexOf('/') + 1);
                return new SchemaInput(
                        publicId,
                        "classpath:/" + SCHEMA_DIRECTORY + fileName,
                        resource.getInputStream()
                );
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load OpenADR schema import: " + systemId, e);
            }
        }
    }

    private static final class SchemaInput implements LSInput {

        private final String publicId;
        private final String systemId;
        private final InputStream byteStream;

        private SchemaInput(String publicId, String systemId, InputStream byteStream) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.byteStream = byteStream;
        }

        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) { }
        @Override public InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(InputStream byteStream) { }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) { }
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { }
        @Override public String getPublicId() { return publicId; }
        @Override public void setPublicId(String publicId) { }
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String baseUri) { }
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String encoding) { }
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean certifiedText) { }
    }
}
