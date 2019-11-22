package ru.openfs.lbpay.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import ru.openfs.lbpay.PaymentGatewayConstants;


@Component
@Profile("prom")
public class DreamkasReceiptRoute extends RouteBuilder {

	@Override
	public void configure() throws Exception {

		// rest("/sber/online").bindingMode(RestBindingMode.xml).consumes("application/xml").produces("application/xml")
		// .get().outType(SberOnlineResponse.class).route().routeId("ProcessSberOnline").process("sberOnline")
		// .endRest();

		// process payment fiscalization
		from("direct:registerSaleReceipt").id("RegisterSaleReceipt")
			.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
				.setHeader(PaymentGatewayConstants.RECEIPT_TYPE,constant("SALE"))
				// audit register receipt
				.bean("audit", "registerReceipt")
				// register receipt
				.bean("dreamkas", "register")
			.end();

		// process refund fiscalization
		from("direct:registerRefundReceipt").id("RegisterRefundReceipt")
			.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
				.setHeader(PaymentGatewayConstants.RECEIPT_TYPE,constant("REFUND"))
				// audit register receipt
				.bean("audit", "registerReceipt")
				// register receipt
				.bean("dreamkas", "register")
			.end();

		// attemp to register ERROR receipt
		rest("/reprocessing/").bindingMode(RestBindingMode.off)
			.get("/{orderNumber}").route().id("ReprocessingReceipt")
				// lookup receipt
				.bean("audit", "getErrorReceipt")
				// status is no success
				.filter(header("mdOrder").isNotNull())
					.log("Reprocessing receipt orderNumber:${header.orderNumber}")
					.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(204))
					.setBody(constant(""))
					// audit register receipt
					.bean("audit", "registerReceipt")
					// try re-register receipt
					.bean("dreamkas", "register")
				.end()
				// receipt not found
				.filter(header("mdOrder").isNull())
					.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
					.setBody(constant("ERROR: ORDER IS NOT FOUND"))
				.end()
			.endRest();
	}
}