package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class DreamkasCleanupCustomerRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // cleanup clients on kabinet.dreamkas
        from("timer:cleanup?period={{dreamkas.cleanup.period}}")
            .id("DreamkasCleanupClients").autoStartup("{{dreamkas.cleanup.enable}}")
            .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
            .setHeader(Exchange.HTTP_PATH, constant("/api/clients"))
            .to("netty-http:{{dreamkas.url}}?ssl=true&sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            .split().jsonpath("$.[*].id")
                .log("Cleanup clientId:${body}")
                .removeHeaders("Content.*")
                .setHeader("Authorization",constant("Bearer {{dreamkas.token}}"))
                .setHeader(Exchange.HTTP_METHOD,constant("DELETE"))
                .setHeader(Exchange.HTTP_PATH, simple("/api/clients/${body}"))
                .setBody(constant(""))
                .to("netty-http:{{dreamkas.url}}?ssl=true&sslContextParameters=#sslContext&throwExceptionOnFailure=true")
            .end();
    }

}