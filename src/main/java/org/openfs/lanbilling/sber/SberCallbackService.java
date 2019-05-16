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

import io.undertow.util.StatusCodes;

@Component("sberCallback")
public class SberCallbackService implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(SberCallbackService.class);

	@Autowired
	LbSoapService lbapi;

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// connect to LB
		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.SERVICE_UNAVAILABLE);
			return;
		}

		// check required parameters
		if (message.getHeader("status") == null || message.getHeader("mdOrder") == null
				|| message.getHeader("operation") == null || message.getHeader("orderNumber") == null) {
			LOG.error("Request parameters");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.METHOD_NOT_ALLOWED);
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
		if (status == 1) {
			return doProcessSuccess(operation, orderNumber, receipt);
		}
		if (status == 0) {
			return doProcessUnsuccess(operation, orderNumber);
		}
		LOG.error("Request has unknown status:{}", status);
		return StatusCodes.NOT_FOUND;
	}

	private int doProcessSuccess(String operation, Long orderNumber, String receipt) {
		LOG.info("Processing status:SUCCESS, orderNumber:{}, operation:{}, receipt:{}", orderNumber, operation,
				receipt);

		// process by operation
		if (operation.equalsIgnoreCase("approved")) {
			LOG.info("NOOP: Approved operation.");
			return StatusCodes.OK;
		}

		if (!operation.matches("deposited|reversed|refunded")) {
			LOG.warn("Unknown operation:{}", operation);
			return StatusCodes.NOT_FOUND;
		}

		// lookup prepayment record
		ServiceResponse response = lbapi.getPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.warn("Prepayment orderNumber:{} not found", orderNumber);
			return StatusCodes.NOT_FOUND;
		}

		LOG.info("Found prepayment orderNumber:{}, amount:{}, agreementId:{}, date:{}]", orderNumber,
				response.getValue(LbSoapService.FIELD_AMOUNT), response.getValue(LbSoapService.FIELD_AGREEMENT_ID),
				response.getValue(LbSoapService.FIELD_PREPAYMENT_PAY_DATE));

		// get prepayment amount
		double amount = (double) response.getValue(LbSoapService.FIELD_AMOUNT);

		// process payment
		if (operation.equalsIgnoreCase("deposited")) {

			// test if has payment
			if (response.getLong(LbSoapService.FIELD_PREPAYMENT_STATUS) == 1) {
				LOG.warn("Prepayment orderNumber:{} already complete", orderNumber);
				return StatusCodes.NOT_FOUND;
			}

			response = lbapi.confirmPrePayment(orderNumber, amount, receipt);
			if (response.isSuccess()) {
				LOG.info("Processed payment orderNumber:{}, amount:{}", orderNumber, amount);
				return StatusCodes.OK;
			}

			// process fault message
			if (response.isFault()) {
				return processFaultResponse((String) response.getBody());
			}

			// no response or internal error
			LOG.error("Internal Server error");
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}

		// process refund and return
		if (response.getValue(LbSoapService.FIELD_PAYMENT_ID) != null
				&& response.getLong(LbSoapService.FIELD_PAYMENT_ID) != 0
				&& response.getValue(LbSoapService.FIELD_RECEIPT) != null) {

			// cancel payment
			response = lbapi.cancelPayment(response.getString(LbSoapService.FIELD_RECEIPT));
			if (response.isSuccess()) {
				LOG.info("Processed refund payment orderNumber:{}, amount:{}", orderNumber, amount);
				return StatusCodes.OK;
			}

			// process fault message
			if (response.isFault()) {
				return processFaultResponse((String) response.getBody());
			}

			// no response or internal error
			LOG.error("Internal Server error");
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}

		// payment not confirmed - try to delete prepayment record
		response = lbapi.cancelPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.error("Payment not found. Error cancel prepayment orderNumber:{}", orderNumber);
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}
		LOG.warn("Payment not found. Cancel prepayment orderNumber:{}", orderNumber);
		return StatusCodes.OK;
	}

	private int doProcessUnsuccess(String operation, Long orderNumber) {
		LOG.info("Processing status:UNSUCCESS, orderNumber:{}, operation:{}", orderNumber, operation);

		// lookup prepayment record
		ServiceResponse response = lbapi.getPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.warn("Prepayment orderNumber:{} not found", orderNumber);
		} else if (response.getLong(LbSoapService.FIELD_PREPAYMENT_STATUS) == 2) {
			LOG.warn("Prepayment orderNumber:{} canceled [{}]", orderNumber,
					response.getString(LbSoapService.FIELD_PREPAYMENT_CANCEL_DATE));
		} else {
			// cancel prepayment
			response = lbapi.cancelPrePayment(orderNumber);
			if (response.isSuccess()) {
				LOG.warn("Cancel prepayment orderNumber:{}", orderNumber);
			} else if (response.isFault()) {
				LOG.warn("Server response fault:{} for orderNumber:{}", response.getBody(), orderNumber);
			} else {
				LOG.error("Internal server errror or cannot cancel orderNumber:{}", orderNumber);
			}
		}
		return StatusCodes.OK;
	}

	private int processFaultResponse(String fault) {
		if (fault.matches(".*not found.*")) {
			LOG.error("Not found:{}", fault);
			return StatusCodes.NOT_FOUND;
		}
		if (fault.matches(".*is cancelled \\(record_id = (\\d+)\\).*")) {
			LOG.warn("Cancelled: {}", fault);
			return StatusCodes.OK;
		}
		if (fault.matches(".*already exists \\(record_id = (\\d+)\\).*")) {
			LOG.warn("Payment duplicate: {}", fault);
			return StatusCodes.OK;
		}
		if (fault.matches(".*cannot be cancelled.*")) {
			LOG.error("Payment cannot be cancelled: {}", fault);
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}
		if (fault.matches(".*already cancelled \\(record_id = (\\d+)\\).*")) {
			LOG.warn("Payment already cancelled: {}", fault);
			return StatusCodes.OK;
		}
		LOG.error("Server return fault response: {}", fault);
		return StatusCodes.INTERNAL_SERVER_ERROR;
	}

}
