package ru.openfs.lbpay.formcheckout;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.validation.PredicateValidationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;
import ru.openfs.lbpay.PaymentGatewayConstants;

import static org.apache.camel.builder.PredicateBuilder.and;

@Component
@Configuration
@Profile("prom")
public class FormCheckoutRoute extends RouteBuilder {

    @Bean
    Predicate isAgreementActive() {
        return new FormAgreementValidator();
    }

    @Override
    public void configure() throws Exception {

        // agreement number must be 6 digits
        Predicate agreementNumber = and(header(PaymentGatewayConstants.FORM_AGREEMENT).isNotNull(),
                header(PaymentGatewayConstants.FORM_AGREEMENT).regex("\\d{6}$"));
        // amount to pay must be >= 10 and < 200000
        Predicate amounToPay = and(header(PaymentGatewayConstants.FORM_AMOUNT).isNotNull(),
                and(header(PaymentGatewayConstants.FORM_AMOUNT).isGreaterThanOrEqualTo(10),
                        header(PaymentGatewayConstants.FORM_AMOUNT).isLessThan(20000)));
        // header("amount").regex("^[1-9][0-9]{1,4}$"));

        // form payment endpoint
        from("rest:get:checkout").routeId("ProcessFormValidate")
            // processing bad request    
            .onException(PredicateValidationException.class)
                .handled(true)
                .log(LoggingLevel.WARN, "uid:[${header.uid}]:${exception.message}").setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.NOT_FOUND)).end()
            // validate request
            .validate(agreementNumber)
            .validate(isAgreementActive())
            // response to valid request
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))
            .setBody(constant(""));

            // process checkout
        from("rest:post:checkout").id("ProcessFormCheckout")
                // processing bad request
                .onException(PredicateValidationException.class).handled(true)
                .log(LoggingLevel.WARN, "${exception.message}").setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.NOT_FOUND)).end()
                // validate request
                .validate(agreementNumber)
                .validate(amounToPay)
                // create orderNumber
                .setHeader(PaymentGatewayConstants.ORDER_NUMBER, method("checkout", "getOrderNumber"))
                .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isEqualTo(0))
                    // response on error create orderNumber
                    .log(LoggingLevel.ERROR, "Error create orderNumber for checkout agreement:${header.uid}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.NOT_FOUND)).setBody(constant(""))
                .end()
                // on success process to bank payment
                .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isGreaterThan(0))
                    .log("Checkout orderNumber:${header.orderNumber} for agreement:${header.uid}, amount:${header.amount}")
                    // register orderNumber on payment service 
                    .process("sberRegisterOrder")
                .end();

            // autopayment EXPERIMENTAL 
            from("rest:post:autopayment").id("ProcessAutopaymentFormCheckout")
                // process error request
                .onException(PredicateValidationException.class).handled(true)
                .log(LoggingLevel.WARN, "${exception.message}").setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.NOT_FOUND)).end()
                // validate request 
                .validate(amounToPay)
                .validate(agreementNumber)
                // process request to create orderNumber
                .setHeader(PaymentGatewayConstants.ORDER_NUMBER, method("checkout", "getOrderNumber"))
                .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isGreaterThan(0))
                    .log("DONE ${header.orderNumber}")
                    // .process("sberRegisterOrder")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.ACCEPTED))
                    .setBody(constant("DONE"))
                .end();
    }

}