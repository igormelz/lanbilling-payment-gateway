package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prom")
public class DreamkasCleanupCustomerRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        //
        from("timer:cleanup?period={{dreamkas.cleanup.period}}").autoStartup("{{dreamkas.cleanup.enable}}")
            .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
            .to("undertow:{{dreamkas.apiUrl}}/clients?throwExceptionOnFailure=false&sslContextParameters=#sslContext&keepAlive=false")
            //.unmarshal().json(JsonLibrary.Jackson)
            //.to("log:INFO?showHeaders=true")
            .split().jsonpath("$.[*].id")
                .log("Cleanup clientId:${body}")
                .setHeader("id", body())
                .removeHeaders("Content.*")
                .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
                .setHeader(Exchange.HTTP_METHOD,constant("DELETE"))
                .setBody(constant(""))
                .toD("undertow:{{dreamkas.apiUrl}}/clients/${header.id}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
            .end();
    }

}