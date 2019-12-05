package ru.openfs.lbpay.sber;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.validation.PredicateValidationException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

import static org.apache.camel.builder.PredicateBuilder.and;

@Component
@Profile("prom")
public class SberCallbackRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // callback request as 
        // {merchant-url}?mdOrder={mdOrder}&orderNumber={orderNumber}&operation={operation}&status={status}
        Predicate request = and(header("status").isNotNull(), header("operation").isNotNull(),
                header("mdOrder").isNotNull(), header("orderNumber").isNotNull());
        // success payment operation
        Predicate successPayment = and(header("status").isEqualTo(1), header("operation").isEqualTo("deposited"));
        // success refund operation
        Predicate successRefund = and(header("status").isEqualTo(1), header("operation").in("refunded", "reversed"));
        // unsuccess operation
        Predicate unsuccess = header("status").isEqualTo("0");
        // approved operation
        Predicate approved = and(header("status").isEqualTo("1"), header("operation").isEqualTo("approved"));

        //rest("/sber/callback").bindingMode(RestBindingMode.off).get().route().id("SberCallback")
        from("rest:get:sber/callback").id("SberCallback")
            // process invalid request
            .onException(PredicateValidationException.class)
                .handled(true)
                .log(LoggingLevel.WARN, "request:[${headers}]:${exception.message}")
                .setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.BAD_REQUEST))
            .end()

            // validate callback request
            .validate(request)

            // process operation
            .choice()
                .when(successPayment)
                    // process payment for orderNumber
                    .process("orderPayment")
                    .to("direct:registerSaleReceipt")                    
                    
                .when(successRefund)
                    // process refund payment by orderNymber and mdOrder   
                    .process("refundPayment")
                    .to("direct:registerRefundReceipt")
                    
                .when(unsuccess)
                    // process cancel prepayment order 
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, method("cancelOrder"))
                   
                .when(approved)
                    // NOOP: response 200 OK 
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))
                    
                .otherwise()
                    // process unknown as error
                    .log(LoggingLevel.ERROR, "Receive unknown request: ${headers}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.BAD_REQUEST))
            .end()

            // response empty body
            .setBody(constant(""))
        .endRest();

    }

}