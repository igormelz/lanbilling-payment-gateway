package org.openfs.lanbilling.sber;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.openfs.lanbilling.dreamkas.DreamkasReceiptService;
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

	@Autowired
	DreamkasReceiptService dreamkas;

//	private <K, V> Stream<K> keys(Map<K, V> map, V value) {
//		return map.entrySet().stream().filter(entry -> value.equals(entry.getValue())).map(Map.Entry::getKey);
//	}

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
			LOG.error("check request parameters");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.BAD_REQUEST);
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
			return doOperation(operation, orderNumber, receipt);
		}
		if (status == 0) {
			return cancelOperation(operation, orderNumber);
		}
		LOG.error("Request has unknown status:{}", status);
		return StatusCodes.NOT_FOUND;
	}

	private int doOperation(String operation, Long orderNumber, String receipt) {
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
		ServiceResponse prepayment = lbapi.getPrePayment(orderNumber);
		if (!prepayment.isSuccess()) {
			LOG.warn("Prepayment orderNumber:{} not found", orderNumber);
			return StatusCodes.NOT_FOUND;
		}
		LOG.info("Found prepayment orderNumber:{}, amount:{}, agreementId:{}, date:[{}]", orderNumber,
				prepayment.getValue(LbSoapService.AMOUNT), prepayment.getValue(LbSoapService.AGREEMENT_ID),
				prepayment.getValue(LbSoapService.PREPAYMENT_PAY_DATE));

		// deposited operation
		if (operation.equalsIgnoreCase("deposited")) {
			if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_PROCESSED) {
				LOG.warn("Payment already processed for orderNumber:{}", orderNumber);
				return StatusCodes.ACCEPTED;
			}
			if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
				LOG.warn("Prepayment canceled for orderNumber:{}", orderNumber);
				return StatusCodes.ACCEPTED;
			} 
			// confirm prepayment
			ServiceResponse payment = lbapi.confirmPrePayment(orderNumber,
					(Double) prepayment.getValue(LbSoapService.AMOUNT), receipt);
			if (payment.isSuccess()) {
				LOG.info("Success processed payment orderNumber:{}, amount:{}", orderNumber,
						prepayment.getValue(LbSoapService.AMOUNT));
				return StatusCodes.OK;
			}
			if (payment.isFault()) {
				// process fault message
				return processFaultResponse((String) payment.getBody());
			}
		}
		// refunded, reversed operations
		if (prepayment.getValue(LbSoapService.PAYMENT_ID) != null && prepayment.getLong(LbSoapService.PAYMENT_ID) != 0
				&& prepayment.getValue(LbSoapService.RECEIPT) != null) {
			// refund payment
			ServiceResponse response = lbapi.cancelPayment(prepayment.getString(LbSoapService.RECEIPT));
			if (response.isSuccess()) {
				LOG.info("Processed refund payment orderNumber:{}, amount:{}", orderNumber,
						prepayment.getValue(LbSoapService.AMOUNT));
				return StatusCodes.OK;
			}
			// process fault message
			if (response.isFault()) {
				return processFaultResponse((String) response.getBody());
			}
			// no response or internal error
			LOG.error("Refunded has server error");
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}
		// payment not confirmed - try to delete prepayment record
		ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.error("Refunded payment not found. Error cancel prepayment orderNumber:{}", orderNumber);
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}
		LOG.warn("Refunded payment not found. Cancel prepayment orderNumber:{}", orderNumber);
		return StatusCodes.OK;
	}
		
			// get account info
//			response = lbapi.getAccount(CodeExternType.AGRM_ID,
//					prepayment.getValue(LbSoapService.AGREEMENT_ID).toString());
//			if (response.isSuccess()) {
//				LOG.info("SUCCCESS - uid:{}, amount:{}, balance:{}, name:{}, phone:{}, email:{}",
//						keys(response.getValues(), prepayment.getLong(LbSoapService.AGREEMENT_ID)).findFirst().get(),
//						prepayment.getValue(LbSoapService.AMOUNT), response.getValue(LbSoapService.TOTAL_BALANCE),
//						response.getString(LbSoapService.NAME), response.getString(LbSoapService.PHONE),
//						response.getString(LbSoapService.EMAIL));
				// process fiscal receipt				
//				dreamkas.fiscalization("Оплата за услуги связи",
//						((Double) prepayment.getValue(LbSoapService.AMOUNT)).longValue(),
//						response.getString(LbSoapService.PHONE), response.getString(LbSoapService.EMAIL));
//			}


	private int cancelOperation(String operation, Long orderNumber) {
		LOG.info("Processing status:UNSUCCESS, orderNumber:{}, operation:{}", orderNumber, operation);

		// lookup prepayment record
		ServiceResponse response = lbapi.getPrePayment(orderNumber);
		if (!response.isSuccess()) {
			LOG.warn("Prepayment orderNumber:{} not found", orderNumber);
		} else if (response.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
			LOG.warn("Prepayment orderNumber:{} already canceled [{}]", orderNumber,
					response.getString(LbSoapService.PREPAYMENT_CANCEL_DATE));
		} else {
			// cancel prepayment
			response = lbapi.cancelPrePayment(orderNumber);
			if (response.isSuccess()) {
				LOG.info("Canceled prepayment orderNumber:{}", orderNumber);
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
