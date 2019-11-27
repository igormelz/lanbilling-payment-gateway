package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prom")
public class DreamkasWebhookRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // Processing Dreamkas Webhook 
        from("rest:post:dreamkas?consumes=application/json")
            .routeId("DreamkasWebhook")
            
            // convert to HashMap
            .unmarshal().json(JsonLibrary.Jackson)
            .log("Processing action:${body[action]} type:${body[type]}")
            
            // process webhook
            .choice()                   
                // process OPERATION webhook
                .when(simple("${body[type]} == 'OPERATION'"))
                    .bean("audit", "updateReceiptOperation(${body[data]})")
        
                // process RECEIPT webhook
                .when(simple("${body[type]} == 'RECEIPT'"))
                    .log("Fiscal receipt operation:${body[data][operationId]}, shift:${body[data][shiftId]}, doc:${body[data][fiscalDocumentNumber]}")
       
                // unparsed data
                .otherwise()
                    .log(LoggingLevel.WARN, "receive unparsed data:${body}")
            .end()
        
            // response no content
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(204))
            .setBody(constant(""))
        .endRest();

    }

}