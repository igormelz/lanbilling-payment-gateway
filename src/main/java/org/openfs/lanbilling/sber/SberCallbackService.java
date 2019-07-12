package org.openfs.lanbilling.sber;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.apache.camel.Message;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.openfs.lanbilling.dreamkas.DreamkasReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("sberCallback")
public class SberCallbackService {
	private static final Logger LOG = LoggerFactory.getLogger(SberCallbackService.class);

	@Autowired
	LbSoapService lbapi;

//	@Autowired
//	DreamkasReceiptService dreamkas;

	private <K, V> Stream<K> keys(Map<K, V> map, V value) {
		return map.entrySet().stream().filter(entry -> value.equals(entry.getValue())).map(Map.Entry::getKey);
	}

	private String getStatus(Long status) {
		return (status == LbSoapService.STATUS_PROCESSED) ? "processed"
				: (status == LbSoapService.STATUS_CANCELED) ? "canceled" : "ready";
	}

	private ServiceResponse lookupPrePayment(Long orderNumber) {
		ServiceResponse prepayment = lbapi.getPrePayment(orderNumber);
		if (prepayment.isSuccess()) {
			LOG.debug("Found prepayment order:{}, amount:{}, created:[{}], status:{} [{}]", orderNumber,
					prepayment.getValue(LbSoapService.AMOUNT), prepayment.getValue(LbSoapService.PREPAYMENT_PAY_DATE),
					prepayment.getLong(LbSoapService.PREPAYMENT_STATUS),
					getStatus(prepayment.getLong(LbSoapService.PREPAYMENT_STATUS)));
			// get account info
			ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_ID,
					prepayment.getValue(LbSoapService.AGREEMENT_ID).toString());
			if (response.isSuccess()) {
				LOG.debug("Account Info by order:{} -- argeement:{}, balance:{}, name:[{}], phone:[{}], email:[{}]",
						orderNumber,
						keys(response.getValues(), prepayment.getValue(LbSoapService.AGREEMENT_ID)).findFirst().get(),
						response.getValue(LbSoapService.TOTAL_BALANCE), response.getString(LbSoapService.NAME),
						response.getString(LbSoapService.PHONE), response.getString(LbSoapService.EMAIL));
				// add account info to prepayment response
				Map<String, Object> values = prepayment.getValues();
				values.put(LbSoapService.AGREEMENT,
						keys(response.getValues(), prepayment.getValue(LbSoapService.AGREEMENT_ID)).findFirst().get());
				values.put(LbSoapService.NAME, response.getString(LbSoapService.NAME));
				values.put(LbSoapService.TOTAL_BALANCE, response.getValue(LbSoapService.TOTAL_BALANCE));
				values.put(LbSoapService.PHONE, response.getString(LbSoapService.PHONE));
				values.put(LbSoapService.EMAIL, response.getString(LbSoapService.EMAIL));
			}
			return prepayment;
		}
		LOG.warn("Fail lookup prepayment order:{} - {}", orderNumber, prepayment.getBody());
		return null;
	}

	@Handler
	public void processPayment(Exchange exchange) throws Exception {
		//public int processPayment(@Header("orderNumber") Long orderNumber, @Header("mdOrder") String receipt) {
		Message message = exchange.getIn();
		Long orderNumber = message.getHeader("orderNumber",Long.class);
		String  mdOrder = message.getHeader("mdOrder", String.class); 
		LOG.info("Processing payment order:{}, mdOrder:{}", orderNumber, mdOrder);

		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.SERVICE_UNAVAILABLE);
			return;
		}

		// lookup LB prepayment record
		ServiceResponse prepayment = lookupPrePayment(orderNumber);
		if (prepayment == null) {
			lbapi.disconnect();
			message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.NOT_FOUND);
			return;
		}

		// validate prepayment status
		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_PROCESSED) {
			LOG.warn("Payment order:{} was processed", orderNumber);
			lbapi.disconnect();
			// return empty success response
			message.setBody(null);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.OK);
		}

		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
			LOG.error("Payment order:{} was canceled [{}]", orderNumber,
					prepayment.getValue(LbSoapService.PREPAYMENT_CANCEL_DATE));
			lbapi.disconnect();
			message.setBody(null);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.OK);
		}

		// confirm prepayment
		ServiceResponse payment = lbapi.confirmPrePayment(orderNumber,
				(Double) prepayment.getValue(LbSoapService.AMOUNT), mdOrder);

		if (payment.isSuccess()) {
			LOG.info("Success payment order:{}, amount:{}, agreement:{}, name:[{}], balance:{}, phone:{}, email:[{}]",
					orderNumber, prepayment.getValue(LbSoapService.AMOUNT),
					prepayment.getValue(LbSoapService.AGREEMENT), prepayment.getValue(LbSoapService.NAME),
					prepayment.getValue(LbSoapService.TOTAL_BALANCE), prepayment.getValue(LbSoapService.PHONE),
					prepayment.getValue(LbSoapService.EMAIL));
			lbapi.disconnect();

			// process fiscal receipt
//			dreamkas.fiscalization(((Double) prepayment.getValue(LbSoapService.AMOUNT)).longValue(),
//					prepayment.getString(LbSoapService.PHONE), prepayment.getString(LbSoapService.EMAIL));

			// return success with body as map parameters
			message.setHeader("amount", prepayment.getValue(LbSoapService.AMOUNT));
			message.setHeader("phone", prepayment.getString(LbSoapService.PHONE));
			message.setHeader("email", prepayment.getString(LbSoapService.EMAIL));
			message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.OK);
			return;
		}

		if (payment.isFault()) {
			LOG.error("Fail payment order:{} - {}",orderNumber, payment.getBody());
		}
		message.setHeader(Exchange.HTTP_RESPONSE_CODE,StatusCodes.NOT_ACCEPTABLE);
	}

	@Handler
	public int processRefund(@Header("orderNumber") Long orderNumber, @Header("mdOrder") String receipt) {
		LOG.info("Processing refund order:{}, receipt:{}", orderNumber, receipt);

		if (!lbapi.connect()) {
			return StatusCodes.SERVICE_UNAVAILABLE;
		}

		// lookup LB prepayment record
		ServiceResponse prepayment = lookupPrePayment(orderNumber);
		if (prepayment == null) {
			lbapi.disconnect();
			return StatusCodes.NOT_FOUND;
		}

		if (prepayment.getValue(LbSoapService.PAYMENT_ID) != null && prepayment.getLong(LbSoapService.PAYMENT_ID) != 0
				&& prepayment.getValue(LbSoapService.RECEIPT) != null) {
			// try refund payment
			ServiceResponse response = lbapi.cancelPayment(prepayment.getString(LbSoapService.RECEIPT));
			lbapi.disconnect();
			if (response.isSuccess()) {
				LOG.info(
						"Success refund order:{}, amount:{}, agreement:{}, name:[{}], balance:{}, phone:{}, email:[{}]",
						orderNumber, prepayment.getValue(LbSoapService.AMOUNT),
						prepayment.getValue(LbSoapService.AGREEMENT), prepayment.getValue(LbSoapService.NAME),
						prepayment.getValue(LbSoapService.TOTAL_BALANCE), prepayment.getValue(LbSoapService.PHONE),
						prepayment.getValue(LbSoapService.EMAIL));
				return StatusCodes.OK;
			}
			if (response.isFault()) {
				LOG.warn("LB return fault:{}", response.getBody());
			}
			return StatusCodes.NOT_ACCEPTABLE;
		} else {
			// payment not confirmed - try to delete prepayment record
			ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
			lbapi.disconnect();
			if (!response.isSuccess()) {
				LOG.error("Refunded payment not found. Error cancel prepayment order:{}", orderNumber);
				return StatusCodes.NOT_ACCEPTABLE;
			}
			LOG.warn("Refunded payment not found. Cancel prepayment order:{}", orderNumber);
			return StatusCodes.OK;
		}
	}

	@Handler
	public int cancelPrePayment(@Header("orderNumber") Long orderNumber, @Header("operation") String operation,
			@Header("mdOrder") String receipt) {
		LOG.info("Processing cancel operation:{}, order:{}, mdOrder:{}", operation, orderNumber, receipt);

		if (!lbapi.connect()) {
			return StatusCodes.SERVICE_UNAVAILABLE;
		}

		// lookup LB prepayment record
		ServiceResponse prepayment = lookupPrePayment(orderNumber);
		if (prepayment == null) {
			lbapi.disconnect();
			return StatusCodes.NOT_FOUND;
		}

		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_PROCESSED) {
			LOG.warn("Payment order:{} was processed", orderNumber);
			lbapi.disconnect();
			return StatusCodes.OK;
		}
		
		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
			LOG.warn("Prepayment order:{} was canceled [{}]", orderNumber,
					prepayment.getString(LbSoapService.PREPAYMENT_CANCEL_DATE));
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		if (operation.equalsIgnoreCase("deposited")) {
			LOG.warn("Received cancel deposited -- do nothing, waiting to success.");
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		// try to cancel prepayment record
		ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
		lbapi.disconnect();
		if (!response.isSuccess()) {
			LOG.error("Error cancel prepayment order:{}", orderNumber);
			return StatusCodes.NOT_ACCEPTABLE;
		}
		LOG.info("Canceled by {} order:{}, amount:{}, agreement:{}, name:[{}], balance:{}, phone:{}, email:[{}]",
				operation, orderNumber, prepayment.getValue(LbSoapService.AMOUNT),
				prepayment.getValue(LbSoapService.AGREEMENT), prepayment.getValue(LbSoapService.NAME),
				prepayment.getValue(LbSoapService.TOTAL_BALANCE), prepayment.getValue(LbSoapService.PHONE),
				prepayment.getValue(LbSoapService.EMAIL));
		return StatusCodes.OK;
	}

}
