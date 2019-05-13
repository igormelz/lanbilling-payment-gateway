package org.openfs.lanbilling.sber;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("sberCallback")
public class SberCallbackService implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(SberCallbackService.class);

	@Autowired
	LbSoapService lbapi;

	private int doProcessSuccess(String operation, Long orderNumber, String receipt) {
		LOG.info("Process successful status for orderNumber:{}, operation:{}, receipt:{}", orderNumber, operation,
				receipt);

		// process by operation code
		if (operation.equalsIgnoreCase("approved")) {
			LOG.info("NOOP: Approved operation.");
			return 200;
		}

		if (!operation.matches("deposited|reversed|refunded")) {
			LOG.error("Received unknown operation:{}", operation);
			return 404;
		}

		// find payment record
		ServiceResponse response = lbapi.getPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.error("PrePayment not found for orderNumber:{}", orderNumber);
			return 404;
		}
		// process answer
		double amount = (double) response.getValue("amount");

		if (operation.equalsIgnoreCase("deposited")) {
			// do confirm payment
			response = lbapi.confirmPrePayment(orderNumber, amount, receipt);
			if (response.isSuccess()) {
				// payment success confirmed
				LOG.info("Processed payment orderNumber:{} on amount:{}", orderNumber, amount);
				return 200;
			}
			// processing fault message
			if (response.isFault()) {
				String fault = (String) response.getBody();
				if (fault.matches(".*not found.*")) {
					LOG.error("Account not found:{}", fault);
					return 404;
				}
				if (fault.matches(".*is cancelled \\(record_id = (\\d+)\\).*")) {
					LOG.warn("Cancelled: {}", fault);
					return 200;
				}
				if (fault.matches(".*already exists \\(record_id = (\\d+)\\).*")) {
					LOG.warn("Payment duplicate: {}", fault);
					return 200;
				}
				// unknown fault
				LOG.error("Server return fault response: {}", fault);
				return 500;
			}

			// no response or internal error
			LOG.error("Internal Server error");
			return 500;
		}

		// process refund and return
		if (response.getValue("paymentid") != null && (Long) response.getValue("paymentid") != 0
				&& response.getValue("receipt") != null) {
			// try cancel payment
			response = lbapi.cancelPayment(response.getValue("receipt").toString());
			if (response.isSuccess()) {
				// payment success confirmed
				LOG.info("Processed refund payment orderNumber:{} on amount:{}", orderNumber, amount);
				return 200;
			}
			if (response.isFault()) {
				String fault = (String) response.getBody();
				if (fault.matches(".*not found.*")) {
					LOG.error("Payment not found:{}", fault);
					return 404;
				}
				if (fault.matches(".*cannot be cancelled.*")) {
					LOG.warn("Payment cannot be cancelled: {}", fault);
					return 500;
				}
				if (fault.matches(".*already cancelled \\(record_id = (\\d+)\\).*")) {
					LOG.warn("Payment already cancelled: {}", fault);
					return 200;
				}
				// unknown fault
				LOG.error("Server fault response: {}", fault);
				return 500;
			}
			// no response or internal error
			LOG.error("Internal Server error");
			return 500;
		}
		
		// payment not confirmed - try to delete prepayment record
		response = lbapi.cancelPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.error("Internal server errror or cannot cancel orderNumber:{}", orderNumber);
			return 500;
		}
		LOG.warn("Deleted PrePayment record for orderNumber:{}", orderNumber);
		return 200;
	}

	private int doProcessUnsuccess(String operation, Long orderNumber) {
		LOG.info("Process unsuccessful status for orderNumber:{} with operation:{}", orderNumber, operation);
		if (operation.startsWith("declined")) {
			ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
			if (!response.isSuccess()) {
				LOG.error("Internal server errror or cannot cancel orderNumber:{}", orderNumber);
				return 200;
			}
			LOG.warn("Deleted PrePayment record for orderNumber:{}", orderNumber);
			return 200;
		}
		LOG.warn("Operation unknown for orderNumber:{}", orderNumber);
		return 200;
	}

	protected int doProcess(int status, String operation, Long orderNumber, String receipt) {
		if (status == 1)
			return doProcessSuccess(operation, orderNumber, receipt);
		if (status == 0)
			return doProcessUnsuccess(operation, orderNumber);
		// unsupported status
		LOG.error("Received unknown status: {}", status);
		return 404;
	}

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// connect to LB
		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
			return;
		}

		// validate params
		if (message.getHeader("status") == null || message.getHeader("mdOrder") == null
				|| message.getHeader("operation") == null || message.getHeader("orderNumber") == null) {
			LOG.error("Params not found");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
			return;
		}

		// process request and set response code
		message.setHeader(Exchange.HTTP_RESPONSE_CODE,
				doProcess(message.getHeader("status", Integer.class), message.getHeader("operation", String.class),
						message.getHeader("orderNumber", Long.class), message.getHeader("mdOrder", String.class)));

		// close connection
		lbapi.disconnect();
	}
}