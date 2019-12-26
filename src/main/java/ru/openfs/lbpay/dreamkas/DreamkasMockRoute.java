package ru.openfs.lbpay.dreamkas;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import ru.openfs.lbpay.dreamkas.model.Operation;

@Component
@Profile("dev")
public class DreamkasMockRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        JacksonDataFormat answer = new JacksonDataFormat(Operation.class);

        from("netty-http:http://localhost:7001/api/receipts?httpMethodRestrict=POST")
                .routeId("DreamkasMockService").unmarshal().json(JsonLibrary.Jackson).log("MOCK REQ:${body}")
                .process(new Processor() {

                    @Override
                    public void process(Exchange exchange) throws Exception {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body = exchange.getIn().getMandatoryBody(Map.class);
                        Operation operation = new Operation();
                        operation.setId(UUID.randomUUID().toString().replace("-", ""));
                        operation.setExternalId(body.get("externalId").toString());
                        operation.setCreatedAt(DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now()));
                        operation.setStatus("PENDING");
                        exchange.getIn().setBody(operation);
                    }

                }).marshal(answer).setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202)).log("MOCK RES:${body}");

        // from("netty-http:https://localhost:7001/api/clients?httpMethodRestrict=GET")
        //         .routeId("DreamkasMockServiceClients")
        //         .setBody(constant("[{\"id\":\"19f78eee\", \"name\":null, \"phone\":\"+12321312\", \"email\":null}]"))
        //         .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200)).log("MOCK CLIENTS:${body}");

        // from("netty-http:https://localhost:7001/api/clients/19f78eee?httpMethodRestrict=DELETE")
        //         .setBody(constant(null)).setHeader(Exchange.HTTP_RESPONSE_CODE, constant(204))
        //         .log("MOCK CLIENTS:DELETE:OK");
    }

}