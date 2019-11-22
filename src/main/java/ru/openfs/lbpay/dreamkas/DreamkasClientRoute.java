package ru.openfs.lbpay.dreamkas;

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
            // submit receipt
            // old: .to("undertow:{{dreamkas.Url}}?sslContextParameters=#sslContext")
            .to("undertow:{{dreamkas.Url}}?sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            // on success response register operation  
            .unmarshal(operation)
            .log("Receipt mdOrder:${body.externalId}, operation:${body.id} [${body.status}]")
            // update receipt status
            .bean("audit", "setReceiptOperation");

    }

}