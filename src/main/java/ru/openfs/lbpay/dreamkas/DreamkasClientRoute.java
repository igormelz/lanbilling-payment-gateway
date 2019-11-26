package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.http.common.HttpOperationFailedException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import ru.openfs.lbpay.dreamkas.model.Operation;
import ru.openfs.lbpay.dreamkas.model.Receipt;

@Component
@Profile("prom")
public class DreamkasClientRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        JacksonDataFormat receipt = new JacksonDataFormat(Receipt.class);
        receipt.setInclude("NON_NULL");
        JacksonDataFormat operation = new JacksonDataFormat(Operation.class);

        from("seda:dreamkasRegisterReceipt").id("DreamkasHttpClient")
            .onException(HttpOperationFailedException.class)
                .useExponentialBackOff().maximumRedeliveries(3).redeliveryDelay(15000)
                .retryAttemptedLogLevel(LoggingLevel.ERROR).useOriginalMessage()
            .end()
            // convert to json 
            .marshal(receipt)
            .log("Build receipt:${body}")
            .setHeader(Exchange.HTTP_PATH, constant("/api/receipts"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setHeader(Exchange.CONTENT_ENCODING, constant("utf-8"))
            .setHeader(Exchange.HTTP_METHOD,constant("POST"))
            .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
            // submit receipt
            .to("undertow:{{dreamkas.url}}?sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            // on success response register operation  
            .unmarshal(operation)
            .log("Receipt mdOrder:${body.externalId}, operation:${body.id} [${body.status}]")
            // update receipt status
            .bean("audit", "setReceiptOperation");

    }

}