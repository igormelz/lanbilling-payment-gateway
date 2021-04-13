package ru.openfs.lbpay.sberonline;

import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.support.processor.validation.PredicateValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;

import static org.apache.camel.builder.PredicateBuilder.and;

import java.util.UUID;

import org.apache.camel.LoggingLevel;

@Component
public class SberOnlineRoute extends RouteBuilder {

    @Autowired
    LbSoapService lbapi;

    @Override
    public void configure() throws Exception {
        ExternalId keyGen = new ExternalId();
        SberOnlineResponseFactory response = new SberOnlineResponseFactory();
        Predicate validAction = and(header(PaymentGatewayConstants.ACTION).isNotNull(),
                header(PaymentGatewayConstants.ACTION).in(PaymentGatewayConstants.ACTION_CHECK,
                        PaymentGatewayConstants.ACTION_PAY));
        Predicate validAccount = header(PaymentGatewayConstants.ACCOUNT).isNotNull();
        Predicate validAmount = and(header(PaymentGatewayConstants.AMOUNT).isNotNull(),
                header(PaymentGatewayConstants.AMOUNT).regex("\\d+\\.\\d{0,2}$"));
        Predicate validPayId = and(header(PaymentGatewayConstants.PAY_ID).isNotNull(),
                header(PaymentGatewayConstants.PAY_ID).regex("\\d+$"));
        Predicate validPayDate = and(header(PaymentGatewayConstants.PAY_DATE).isNotNull(),
                header(PaymentGatewayConstants.PAY_DATE)
                    .regex("(\\d{2}).(\\d{2}).(\\d{4})_(\\d{2}):(\\d{2}):(\\d{2})$"));

        rest("/sber/online").bindingMode(RestBindingMode.xml).consumes("application/xml").produces("application/xml")
            .get().id("SberOnline").outType(SberOnlineResponse.class)
            .route().routeId("ProcessSberOnline")
                .onException(PredicateValidationException.class).handled(true)
                    .log(LoggingLevel.ERROR, "Wrong parameters")
                    .setBody(method(response, "unknownRequest"))
                .end()
                .log("Process request: ${headers.CamelHttpQuery}")
                .validate(validAction).validate(validAccount)
                .choice()
                    .when(header(PaymentGatewayConstants.ACTION).isEqualToIgnoreCase(PaymentGatewayConstants.ACTION_CHECK))
                        // process check account
                        .log("Process check agreement:${header.ACCOUNT}")
                        .bean(lbapi,"processCheckPayment")
                    .otherwise()
                        // process payment
                        .validate(validAmount).validate(validPayId).validate(validPayDate)
                        .log("Process payment:${header.PAY_ID} agreement:${header.ACCOUNT} amount:${header.AMOUNT}")
                        .bean(lbapi,"processDirectPayment")
                        // check success
                        .filter(simple("${body.getCode} == 0"))
                            .setHeader(PaymentGatewayConstants.ORDER_NUMBER, header(PaymentGatewayConstants.PAY_ID))
                            .setHeader(PaymentGatewayConstants.SBER_ORDER_NUMBER, method(keyGen,"getId"))
                            .setHeader(PaymentGatewayConstants.ORDER_AMOUNT, simple("${body.paymentInfo.amount}"))
                            .setHeader(PaymentGatewayConstants.CUSTOMER_PHONE, simple("${body.paymentInfo.customerPhone}"))
                            .setHeader(PaymentGatewayConstants.CUSTOMER_EMAIL, simple("${body.paymentInfo.customerEmail}"))
                            .setHeader(PaymentGatewayConstants.RECEIPT_TYPE,constant("SALE"))
                            // audit register receipt
                            .bean("audit", "registerReceipt")
                            // register receipt
                            .bean("ofdReceipt", "register")
                        .end()
                    .end()
                    // remove work headers
                    .removeHeaders("(ACTION|ACCOUNT|PAY_ID|PAY_DATE|AMOUNT|phone|email|mdOrder|orderNumber|receiptType)");
    }
    // gen externalId
    class ExternalId {
        public ExternalId() {}
        public String getId() {
            return UUID.randomUUID().toString();
        }
    }
}