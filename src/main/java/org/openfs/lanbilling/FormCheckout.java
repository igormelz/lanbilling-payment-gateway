package org.openfs.lanbilling;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.openfs.lanbilling.sber.SberAcquiringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("formCheckout")
@Configuration
public class FormCheckout implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(FormCheckout.class);

	@Autowired
	LbSoapService lbapi;

	@Autowired
	SberAcquiringService sberCardService;

	protected void checkout(Message message) {
		final String account = message.getHeader("uid", String.class);
		final double amount = message.getHeader("amount", Double.class);
		LOG.info("Processing checkout uid:{}, amount:{}", account, amount);

		if (lbapi.connect()) {
			// verify account
			ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
			if (response.isSuccess() && response.getValue(account) != null) {
				// insert prepayment record
				response = lbapi.insertPrePayment(response.getLong(account), amount);
				if (response.isSuccess()) {
					sberCardService.callService(response.getLong(LbSoapService.ORDER_NUMBER), amount, message);
				} else {
					LOG.error("Insert prepayment error for uid:{}", account);
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
				}
			} else {
				LOG.warn("uid:{} not found", account);
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
			}
			lbapi.disconnect();
		} else {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
		}
	}

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// validate parameters
		if (message.getHeader("uid") == null || message.getHeader("amount") == null) {
			LOG.error("Unknown checkout parameters");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.BAD_REQUEST);
			return;
		}
		checkout(message);
	}
}
