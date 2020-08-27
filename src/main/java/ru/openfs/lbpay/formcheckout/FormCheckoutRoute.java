package ru.openfs.lbpay.formcheckout;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.validation.PredicateValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;

import static org.apache.camel.builder.PredicateBuilder.and;

@Component
@Configuration
public class FormCheckoutRoute extends RouteBuilder {

    @Value("${form.agreement.pattern}")
    private String formAgreementPattern;

    @Value("${form.amount.pattern}")
    private String formAmountPattern;

    @Autowired
    LbSoapService lbapi;

    @Override
    public void configure() throws Exception {

        // agreement number must be 6+ digits
        Predicate agreementNumber = and(header(PaymentGatewayConstants.FORM_AGREEMENT).isNotNull(),
                header(PaymentGatewayConstants.FORM_AGREEMENT).regex(formAgreementPattern));
        // amount to pay must be >= 10 and < 20000
        Predicate amounToPay = and(header(PaymentGatewayConstants.FORM_AMOUNT).isNotNull(),
                and(header(PaymentGatewayConstants.FORM_AMOUNT).regex(formAmountPattern),
                        and(header(PaymentGatewayConstants.FORM_AMOUNT).isGreaterThanOrEqualTo(10),
                                header(PaymentGatewayConstants.FORM_AMOUNT).isLessThan(20000))));

        // process form test agreement
        rest("/checkout")
            .get().enableCORS(true)
            .route().routeId("ProcessFormValidate")
                .onException(PredicateValidationException.class)
                    .handled(true)
                    .log(LoggingLevel.WARN, "Validation failed for uid=${header.uid}")
                    .setBody(constant(""))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE).constant(PaymentGatewayConstants.BAD_REQUEST)
                .end()
                .validate(agreementNumber).validate(method(lbapi, "isActiveAgreement").isEqualTo(true))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(PaymentGatewayConstants.OK))
                .setBody(constant(""));

        // process form checkout
        from("rest:post:checkout").id("ProcessFormCheckout")
            .onException(PredicateValidationException.class)
                .handled(true)
                .log(LoggingLevel.WARN, "Validation failed for uid=${header.uid}, amount=${header.amount}")
                .setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(PaymentGatewayConstants.BAD_REQUEST))
            .end()
            .validate(agreementNumber).validate(amounToPay)
            // create pre payment orderNumber
            .setHeader(PaymentGatewayConstants.ORDER_NUMBER, method(lbapi, "createPrePaymentOrder"))
            .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isEqualTo(0))
                // response on error create orderNumber
                .log(LoggingLevel.ERROR, "Error create orderNumber for checkout agreement:${header.uid}")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(PaymentGatewayConstants.NOT_FOUND))
                .setBody(constant(""))
            .end()
            .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isGreaterThan(0))
                // on success process to bank payment
                .log("Checkout orderNumber:${header.orderNumber} for agreement:${header.uid}, amount:${header.amount}")
                // register orderNumber on payment service
                .process("sberRegisterOrder")
            .end();

        // autopayment EXPERIMENTAL
        from("rest:post:autopayment").id("ProcessAutopaymentFormCheckout")
            .onException(PredicateValidationException.class)
                .handled(true)
                .log(LoggingLevel.WARN, "${exception.message}")
                .setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(PaymentGatewayConstants.NOT_FOUND))
            .end()
            .validate(amounToPay).validate(agreementNumber)
            // process request to create orderNumber
            .setHeader(PaymentGatewayConstants.ORDER_NUMBER, method(lbapi, "createPrePaymentOrder"))
            .filter(header(PaymentGatewayConstants.ORDER_NUMBER).isGreaterThan(0))
                .log("DONE ${header.orderNumber}")
                // .process("sberRegisterOrder")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(PaymentGatewayConstants.ACCEPTED))
                .setBody(constant("DONE"))
            .end();
    }

}