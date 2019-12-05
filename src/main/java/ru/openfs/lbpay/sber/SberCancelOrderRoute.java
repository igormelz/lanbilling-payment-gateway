package ru.openfs.lbpay.sber;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prom")
public class SberCancelOrderRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
      // Sberbank Register Order
		from("direct:sberRegisterOrder").id("SberHttpClient")
            .onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR, "register failed with exception:${exception}")
            .end()
            .to("undertow:{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
            .filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
                // convert success json message to map
                .unmarshal().json(JsonLibrary.Jackson)
            .end()
            .filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
                .log(LoggingLevel.ERROR, "error response code:${header.CamelHttpResponseCode}. body:[${body}]")
            .end();
    }

    
}