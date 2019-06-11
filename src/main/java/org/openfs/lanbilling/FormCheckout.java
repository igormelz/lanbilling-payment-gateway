package org.openfs.lanbilling;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
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

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.setBody("");
		
		final String account = message.getHeader("uid", String.class);
		final double amount = message.getHeader("amount", Double.class);
		LOG.info("Processing checkout uid:{}, amount:{}", account, amount);

		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}

		// LB: verify account by agreement number
		ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
		if (!response.isSuccess() || response.getValue(account) == null) {
			lbapi.disconnect();
			LOG.error("uid:{} not found", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
			return;
		}

		// LB: insert prepayment record for agreementId and amount.
		response = lbapi.insertPrePayment(response.getLong(account), amount);
		lbapi.disconnect();

		if (!response.isSuccess()) {
			LOG.error("Insert prepayment error for uid:{}", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
			return;
		}
		
		LOG.info("Create prepayment orderNumber:{} for uid:{}, amount:{}",response.getLong(LbSoapService.ORDER_NUMBER),account,amount);
		message.setHeader("orderNumber", response.getLong(LbSoapService.ORDER_NUMBER));
	}
}
