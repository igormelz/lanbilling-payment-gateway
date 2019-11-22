package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prom")
public class DreamkasWebhookRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // Processing Dreamkas Webhook 
        rest("/dreamkas").bindingMode(RestBindingMode.off)
            .post().route().id("DreamkasWebhook")
            
            // convert to HashMap
            .unmarshal().json(JsonLibrary.Jackson)
            .log("Processing action:${body[action]} type:${body[type]}")
            
            // process OPERATION
            .filter(simple("${body[type]} == 'OPERATION'"))
                .filter(simple("${body[data][status]} == 'ERROR'"))
                    // logging operation error
                    .log(LoggingLevel.ERROR,
                "Receipt mdOrder:${body[data][externalId]}, operation:${body[data][id]} [${body[data][data][error][code]}]")
                .end()
                .filter(simple("${body[data][status]} != 'ERROR'"))
                    // logging operation status
                    .log("Receipt mdOrder:${body[data][externalId]}, operation:${body[data][id]} [${body[data][status]}]")
                .end()
                .bean("audit", "updateReceiptOperation(${body[data]})")
            .end()
        
            // process RECEIPT
            .filter(simple("${body[type]} == 'RECEIPT'"))
                .log("Fiscal receipt operation:${body[data][operationId]}, shift:${body[data][shiftId]}, doc:${body[data][fiscalDocumentNumber]}")
            .end()
       
            // unparsed data
            .filter(simple("${body[type]} not in 'OPERATION,RECEIPT'"))
                .log(LoggingLevel.WARN, "receive unparsed data:${body}")
            .end()
        
            // response no content
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(204))
            .setBody(constant(""))
        .endRest();

    }

}