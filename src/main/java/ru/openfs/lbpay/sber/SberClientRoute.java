package ru.openfs.lbpay.sber;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

@Component
public class SberClientRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        // Sberbank Register Order
        from("direct:sberRegisterOrder").id("SberRestClient").onException(Exception.class).handled(true)
                .log(LoggingLevel.ERROR, "${exception.message}").end()
                .to("netty-http:{{sber.Url}}?ssl=true&throwExceptionOnFailure=false&sslContextParameters=#sslContext")
                .filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
                // convert success json message to map
                .unmarshal().json(JsonLibrary.Jackson).end()
                // process error 
                .filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
                .log(LoggingLevel.ERROR, "error response code:${header.CamelHttpResponseCode}. body:[${body}]").end();
    }

}