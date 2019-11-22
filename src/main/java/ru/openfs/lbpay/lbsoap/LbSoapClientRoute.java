package ru.openfs.lbpay.lbsoap;

import javax.xml.ws.soap.SOAPFaultException;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.soap.SoapJaxbDataFormat;
import org.apache.camel.dataformat.soap.name.ServiceInterfaceStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lb.api3.Api3PortType;

@Component
@Profile("prom")
public class LbSoapClientRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        SoapJaxbDataFormat lbsoap = new SoapJaxbDataFormat("lb.api3",
        new ServiceInterfaceStrategy(Api3PortType.class, true));

        from("direct:lbsoap").id("LbSoapAdapter")
            .onException(SOAPFaultException.class)
                // process soap exception
                .handled(true)
                .log(LoggingLevel.ERROR, "${exception.message}")
            .end()

            // marshalling body to soap message 
            .marshal(lbsoap)
            
            // post request to endpoint
            .setHeader(Exchange.HTTP_METHOD).constant("POST")
            .to("undertow:http://{{lbcore}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler")
            
            // process response 
            .filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
                // convert error message to string
                .transform(xpath("//detail/text()", String.class))
                .log(LoggingLevel.ERROR, "${body}")
            .end()
            
            .filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
                // unmarshall soap message 
                .unmarshal(lbsoap)
            .end();
    }
    
}