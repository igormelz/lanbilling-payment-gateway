package ru.openfs.lbpay.sber;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;
import ru.openfs.lbpay.lbsoap.model.LbPaymentInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("orderPayment")
@Profile("prom")
public class SberCallbackPayment implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(SberCallbackPayment.class);

	@Autowired
	LbSoapService lbapi;

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		Long orderNumber = message.getHeader("orderNumber", Long.class);
		String mdOrder = message.getHeader("mdOrder", String.class);
		LOG.info("Processing payment orderNumber:{}, mdOrder:{}", orderNumber, mdOrder);

		LbPaymentInfo payment = lbapi.processPaymentOrder(orderNumber, mdOrder);
		if (payment == null) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}
		// return success with body as map parameters
		message.setHeader(PaymentGatewayConstants.ORDER_AMOUNT, payment.getAmount());
		message.setHeader(PaymentGatewayConstants.CUSTOMER_PHONE, payment.getCustomerPhone());
		message.setHeader(PaymentGatewayConstants.CUSTOMER_EMAIL, payment.getCustomerEmail());
		message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.OK);
	}

}
