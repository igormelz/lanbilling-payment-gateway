package ru.openfs.lbpay.dreamkas;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
        Supplier<String> errorMessage = () -> {
            return "{\"status\":400,\"code\":\"E_VALIDATION_FAILED\",\"message\":\"Ошибка валидации\",\"data\":{\"errors\":[{\"code\":\"E_VALIDATION_ARRAY_BASE\",\"message\":\"Поле `tags` должно быть массивом\"}]}}";
        };

        from("undertow:http://127.0.0.1:7001/service?httpMethodRestrict=POST&useStreaming=false")
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

        from("undertow:http://127.0.0.1:7001/serviceError?httpMethodRestrict=POST&useStreaming=false")
                .routeId("DreamkasMockServiceError").setBody(errorMessage)
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400)).log("MOCK ERR:${body}");
    }

}