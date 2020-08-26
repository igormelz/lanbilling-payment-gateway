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

import org.apache.camel.LoggingLevel;

@Component
public class SberOnlineRoute extends RouteBuilder {

    @Autowired
    LbSoapService lbapi;

    @Override
    public void configure() throws Exception {

        // restConfiguration().component("servlet").contextPath("/pay").dataFormatProperty(
        // "com.fasterxml.jackson.databind.SerializationFeature.disableFeatures",
        // "WRITE_NULL_MAP_VALUES");

        SberOnlineResponseFactory response = new SberOnlineResponseFactory();
        Predicate validAction = and(header(PaymentGatewayConstants.ACTION).isNotNull(),
                header(PaymentGatewayConstants.ACTION).in(PaymentGatewayConstants.ACTION_CHECK,
                        PaymentGatewayConstants.ACTION_PAY));
        Predicate validAccount = and(header(PaymentGatewayConstants.ACCOUNT).isNotNull(),
                header(PaymentGatewayConstants.ACCOUNT).regex("\\d{6,7}$"));
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
                    .log(LoggingLevel.ERROR, "Wrong required parameters")
                    .setBody(method(response, "unknownRequest"))
                .end()
                .log("Process request: ${headers.CamelHttpQuery}")
                .validate(validAction).validate(validAccount)
                .choice()
                    .when(header(PaymentGatewayConstants.ACTION).isEqualToIgnoreCase(PaymentGatewayConstants.ACTION_CHECK))
                        //
                        // process check account
                        //
                        // .setHeader("uid", header(PaymentGatewayConstants.ACCOUNT))
                        .log("Process check agreement:${header.ACCOUNT}")
                        .bean(lbapi,"processCheckPayment")
                        // .choice()
                        //     .when(method(lbapi, "isActiveAgreement"))
                        //         .log("Response check agreement:${header.uid} - OK")
                        //         .setBody(method(response, "success"))
                        //     .otherwise()
                        //         .log(LoggingLevel.ERROR, "Response check agreement:${header.uid} - NOT FOUND")
                        //         .setBody(method(response, "accountNotFound"))
                        // .endChoice()
                    .otherwise()
                        //
                        // process payment
                        //
                        .validate(validAmount).validate(validPayId).validate(validPayDate)
                        .setHeader("uid", header(PaymentGatewayConstants.ACCOUNT))
                        .log("Process payment:${header.PAY_ID} agreement:${header.uid} amount:${header.AMOUNT}")
                        .choice()
                            .when(method(lbapi, "isActiveAgreement"))
                                .setBody(method(lbapi,"processDirectPayment"))
                            .otherwise()
                                .log(LoggingLevel.ERROR, "Response payment:${header.PAY_ID} agreement:${header.uid} - NOT FOUND")
                                .setBody(method(response, "accountNotFound"))
                        .endChoice()
                    .end()
                    // remove work headers
                    .removeHeaders("(ACTION|ACCOUNT|uid|PAY_ID|PAY_DATE|AMOUNT)");
    }

}