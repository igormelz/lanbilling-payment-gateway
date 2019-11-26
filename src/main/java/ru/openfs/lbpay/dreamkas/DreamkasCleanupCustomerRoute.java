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

        // cleanup clients on kabinet.dreamkas
        from("timer:cleanup?period={{dreamkas.cleanup.period}}").autoStartup("{{dreamkas.cleanup.enable}}")
            .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
            .setHeader(Exchange.HTTP_PATH, constant("/api/clients"))
            .to("undertow:{{dreamkas.url}}?sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            .split().jsonpath("$.[*].id")
                .log("Cleanup clientId:${body}")
                .setHeader("id", body())
                .removeHeaders("Content.*")
                .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
                .setHeader(Exchange.HTTP_METHOD,constant("DELETE"))
                .setHeader(Exchange.HTTP_PATH, simple("/api/clients/${header.id}"))
                .setBody(constant(""))
                .to("undertow:{{dreamkas.url}}?sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            .end();
    }

}