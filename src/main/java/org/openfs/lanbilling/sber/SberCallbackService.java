package org.openfs.lanbilling.sber;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("sberCallback")
public class SberCallbackService implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(SberCallbackService.class);
	private static final Marker EMAIL_ALERT = MarkerFactory.getMarker("EMAIL_ALERT");

	@Autowired
	LbSoapService lbapi;

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// connect to LB
		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
			return;
		}

		// validate query parameters
		if (message.getHeader("status") == null || message.getHeader("mdOrder") == null
				|| message.getHeader("operation") == null || message.getHeader("orderNumber") == null) {
			LOG.error("Some query parameter is absent");
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

	protected int doProcess(int status, String operation, Long orderNumber, String receipt) {
		if (status == 1)
			return doProcessSuccess(operation, orderNumber, receipt);
		if (status == 0)
			return doProcessUnsuccess(operation, orderNumber);
		// unsupported status
		LOG.error("Received unknown status: {}", status);
		return 404;
	}

	private int doProcessSuccess(String operation, Long orderNumber, String receipt) {
		LOG.info("Processing successful status for orderNumber:{}, operation:{}, receipt:{}", orderNumber, operation,
				receipt);

		// process by operation code
		if (operation.equalsIgnoreCase("approved")) {
			LOG.info("NOOP: Approved operation.");
			return 200;
		}

		if (!operation.matches("deposited|reversed|refunded")) {
			LOG.error(EMAIL_ALERT, "Received unknown operation:{}", operation);
			return 404;
		}

		// find payment record
		ServiceResponse response = lbapi.getPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.error(EMAIL_ALERT, "PrePayment not found for orderNumber:{}", orderNumber);
			return 404;
		}
		// process answer
		double amount = (double) response.getValue(LbSoapService.FIELD_AMOUNT);

		if (operation.equalsIgnoreCase("deposited")) {
			// do confirm payment
			response = lbapi.confirmPrePayment(orderNumber, amount, receipt);
			if (response.isSuccess()) {
				// payment success confirmed
				LOG.info(EMAIL_ALERT, "Processed payment orderNumber:{} on amount:{}", orderNumber, amount);
				return 200;
			}
			// processing fault message
			if (response.isFault()) {
				return processFaultResponse((String) response.getBody());
			}

			// no response or internal error
			LOG.error(EMAIL_ALERT, "Internal Server error");
			return 500;
		}

		// process refund and return
		if (response.getValue(LbSoapService.FIELD_PAYMENT_ID) != null
				&& response.getLong(LbSoapService.FIELD_PAYMENT_ID) != 0
				&& response.getValue(LbSoapService.FIELD_RECEIPT) != null) {
			// try cancel payment
			response = lbapi.cancelPayment(response.getString(LbSoapService.FIELD_RECEIPT));
			if (response.isSuccess()) {
				// payment success confirmed
				LOG.info(EMAIL_ALERT, "Processed refund payment orderNumber:{} on amount:{}", orderNumber, amount);
				return 200;
			}
			if (response.isFault()) {
				return processFaultResponse((String) response.getBody());
			}
			// no response or internal error
			LOG.error(EMAIL_ALERT, "Internal Server error");
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
		LOG.info("Processing unsuccessful status for orderNumber:{} with operation:{}", orderNumber, operation);
		ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
		if (response.isSuccess()) {
			LOG.warn("Deleted PrePayment record for orderNumber:{}", orderNumber);
		} else if (response.isFault()) {
			LOG.warn("Server response fault:{} for orderNumber:{}", response.getBody(), orderNumber);
		} else {
			LOG.error("Internal server errror or cannot cancel orderNumber:{}", orderNumber);
		}
		return 200;
	}

	private int processFaultResponse(String fault) {
		if (fault.matches(".*not found.*")) {
			LOG.error(EMAIL_ALERT, "Not found:{}", fault);
			return 404;
		}
		if (fault.matches(".*is cancelled \\(record_id = (\\d+)\\).*")) {
			LOG.warn(EMAIL_ALERT, "Cancelled: {}", fault);
			return 200;
		}
		if (fault.matches(".*already exists \\(record_id = (\\d+)\\).*")) {
			LOG.warn(EMAIL_ALERT, "Payment duplicate: {}", fault);
			return 200;
		}
		if (fault.matches(".*cannot be cancelled.*")) {
			LOG.warn(EMAIL_ALERT, "Payment cannot be cancelled: {}", fault);
			return 500;
		}
		if (fault.matches(".*already cancelled \\(record_id = (\\d+)\\).*")) {
			LOG.warn(EMAIL_ALERT, "Payment already cancelled: {}", fault);
			return 200;
		}
		LOG.error(EMAIL_ALERT, "Server return fault response: {}", fault);
		return 500;
	}

}
