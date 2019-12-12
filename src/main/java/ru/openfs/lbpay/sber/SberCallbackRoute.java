package ru.openfs.lbpay.sber;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.validation.PredicateValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;
import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;

import static org.apache.camel.builder.PredicateBuilder.and;

@Component
@Profile("prom")
public class SberCallbackRoute extends RouteBuilder {

    @Autowired
    private LbSoapService lbapi;

    @Override
    public void configure() throws Exception {

        // mdOrder={mdOrder}&orderNumber={orderNumber}&operation={operation}&status={status}
        Predicate request = and(header("status").isNotNull(), header("operation").isNotNull(),
                header("mdOrder").isNotNull(), header("orderNumber").isNotNull());
        Predicate payment = and(header("status").isEqualTo(1), header("operation").isEqualTo("deposited"));
        Predicate refund = and(header("status").isEqualTo(1), header("operation").in("refunded", "reversed"));
        Predicate unsuccess = header("status").isEqualTo("0");
        Predicate approved = and(header("status").isEqualTo("1"), header("operation").isEqualTo("approved"));

        // sberbank callback processing
        from("rest:get:sber/callback").id("SberCallback")
            // process invalid request
            .onException(PredicateValidationException.class)
                .handled(true)
                .log(LoggingLevel.WARN, "${exception.message}")
                .setBody(constant(""))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.BAD_REQUEST))
            .end()
            // validate request
            .validate(request)
            // set deafult response code as error
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.INTERNAL_SERVER_ERROR))
            // process request
            .choice()
                .when(payment)
                    .to("direct:payment")
                .when(and(unsuccess,header("operation").isEqualToIgnoreCase("deposited")))
                    // workaround for cancel deposited operation 
                    .log(LoggingLevel.WARN,"Do not cancel orderNumber:${header.orderNumber} by operation:deposited. Waiting to success.")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))
                .when(unsuccess)
                    .log("Processing cancel orderNumber:${header.orderNumber} by operation:${header.operation}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, method(lbapi,"processCancelPrePayment"))
                .when(refund)
                    .to("direct:refund")
                .when(approved)
                    // NOOP: response 200 OK 
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))
                .otherwise()
                    // process unknown as error
                    .log(LoggingLevel.ERROR, "Receive unknown request: ${header.CamelHttpQuery}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.BAD_REQUEST))
            .end()
            .setBody(constant(""));
        
        from("direct:payment").id("ProcessPayment")
            .log("Processing payment orderNumber:${header.orderNumber}, mdOrder:${header.mdOrder}")
            .bean(lbapi,"processPaymentOrder")
            .filter(body().isNotNull())
                .setHeader(PaymentGatewayConstants.ORDER_AMOUNT,simple("${body.amount}"))
                .setHeader(PaymentGatewayConstants.CUSTOMER_PHONE, simple("${body.customerPhone}"))
                .setHeader(PaymentGatewayConstants.CUSTOMER_EMAIL, simple("${body.customerEmail}"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))        
				.setHeader(PaymentGatewayConstants.RECEIPT_TYPE,constant("SALE"))
				// audit register receipt
				.bean("audit", "registerReceipt")
				// register receipt
				.bean("ofdReceipt", "register")
			.end();

        // process refund payment
        from("direct:refund").id("ProcessRefund")
            .log("Processing refund orderNumber:${header.orderNumber}, mdOrder:${header.mdOrder}")
            .bean(lbapi,"processRefundOrder")
            .filter(body().isNotNull())
                .setHeader(PaymentGatewayConstants.ORDER_AMOUNT,simple("${body.amount}"))
                .setHeader(PaymentGatewayConstants.CUSTOMER_PHONE, simple("${body.customerPhone}"))
		        .setHeader(PaymentGatewayConstants.CUSTOMER_EMAIL, simple("${body.customerEmail}"))
		        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(StatusCodes.OK))
				.setHeader(PaymentGatewayConstants.RECEIPT_TYPE,constant("REFUND"))
				// audit register receipt
				.bean("audit", "registerReceipt")
				// register receipt
				.bean("ofdReceipt", "register")
            .end();

    }

}