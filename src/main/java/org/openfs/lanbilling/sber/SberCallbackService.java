package org.openfs.lanbilling.sber;

import java.util.Map;
import java.util.stream.Stream;

import org.apache.camel.Handler;
import org.apache.camel.Header;
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

	@Autowired
	DreamkasReceiptService dreamkas;

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
			LOG.info("Found prepayment orderNumber:{}, amount:{}, created:[{}], status:{} [{}]",
					orderNumber, prepayment.getValue(LbSoapService.AMOUNT),
					prepayment.getValue(LbSoapService.PREPAYMENT_PAY_DATE),
					prepayment.getLong(LbSoapService.PREPAYMENT_STATUS),
					getStatus(prepayment.getLong(LbSoapService.PREPAYMENT_STATUS)));
			// get account info
			ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_ID,
					prepayment.getValue(LbSoapService.AGREEMENT_ID).toString());
			if (response.isSuccess()) {
				LOG.info("Account Info by orderNumber:{} -- argeement:{}, balance:{}, name:[{}], phone:[{}], email:[{}]",
						orderNumber,
						keys(response.getValues(), prepayment.getValue(LbSoapService.AGREEMENT_ID)).findFirst().get(),
						response.getValue(LbSoapService.TOTAL_BALANCE), response.getString(LbSoapService.NAME),
						response.getString(LbSoapService.PHONE), response.getString(LbSoapService.EMAIL));
			}
			return prepayment;
		}
		LOG.error("Prepayment orderNumber:{} not found", orderNumber);
		return null;
	}

	@Handler
	public int processPayment(@Header("orderNumber") Long orderNumber, @Header("mdOrder") String receipt) {
		LOG.info("Processing payment orderNumber:{}, receipt:{}", orderNumber, receipt);

		if (!lbapi.connect()) {
			return StatusCodes.SERVICE_UNAVAILABLE;
		}

		// lookup LB prepayment record
		ServiceResponse prepayment = lookupPrePayment(orderNumber);
		if (prepayment == null) {
			lbapi.disconnect();
			return StatusCodes.NOT_FOUND;
		}

		// validate prepayment status
		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_PROCESSED) {
			LOG.warn("Payment already processed for orderNumber:{}", orderNumber);
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
			LOG.error("Payment fail for orderNumber:{} - prepayment canceled [{}]", orderNumber,
					prepayment.getValue(LbSoapService.PREPAYMENT_CANCEL_DATE));
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		// confirm prepayment
		ServiceResponse payment = lbapi.confirmPrePayment(orderNumber,
				(Double) prepayment.getValue(LbSoapService.AMOUNT), receipt);

		if (payment.isSuccess()) {
			LOG.info("Payment success for orderNumber:{}, amount:{}", orderNumber,
					prepayment.getValue(LbSoapService.AMOUNT));
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		if (payment.isFault()) {
			LOG.warn("LB return fault:{}", payment.getBody());
		}
		return StatusCodes.NOT_ACCEPTABLE;
	}

	@Handler
	public int processRefund(@Header("orderNumber") Long orderNumber, @Header("mdOrder") String receipt) {
		LOG.info("Processing refund orderNumber:{}, receipt:{}", orderNumber, receipt);

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
				LOG.info("Success refund payment orderNumber:{}, amount:{}", orderNumber,
						prepayment.getValue(LbSoapService.AMOUNT));
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
				LOG.error("Refunded payment not found. Error cancel prepayment orderNumber:{}", orderNumber);
				return StatusCodes.NOT_ACCEPTABLE;
			}
			LOG.warn("Refunded payment not found. Cancel prepayment orderNumber:{}", orderNumber);
			return StatusCodes.OK;
		}
	}

	// process fiscal receipt
//				dreamkas.fiscalization("Оплата за услуги связи",
//						((Double) prepayment.getValue(LbSoapService.AMOUNT)).longValue(),
//						response.getString(LbSoapService.PHONE), response.getString(LbSoapService.EMAIL));
//			}

	@Handler
	public int cancelPrePayment(@Header("orderNumber") Long orderNumber, @Header("operation") String operation,
			@Header("mdOrder") String receipt) {
		LOG.info("Processing cancel operation:{}, orderNumber:{}, receipt:{}", operation, orderNumber, receipt);

		if (!lbapi.connect()) {
			return StatusCodes.SERVICE_UNAVAILABLE;
		}

		// lookup LB prepayment record
		ServiceResponse prepayment = lookupPrePayment(orderNumber);
		if (prepayment == null) {
			lbapi.disconnect();
			return StatusCodes.NOT_FOUND;
		}

		if (prepayment.getLong(LbSoapService.PREPAYMENT_STATUS) == LbSoapService.STATUS_CANCELED) {
			LOG.warn("Prepayment orderNumber:{} already canceled [{}]", orderNumber,
					prepayment.getString(LbSoapService.PREPAYMENT_CANCEL_DATE));
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		if (operation.equalsIgnoreCase("deposited")) {
			LOG.warn("Received unsuccess deposited -- do nothing, waiting to success.");
			lbapi.disconnect();
			return StatusCodes.OK;
		}

		// try to cancel prepayment record
		ServiceResponse response = lbapi.cancelPrePayment(orderNumber);
		lbapi.disconnect();
		if (!response.isSuccess()) {
			LOG.error("Error cancel prepayment orderNumber:{}", orderNumber);
			return StatusCodes.NOT_ACCEPTABLE;
		}
		LOG.info("Prepayment success canceled orderNumber:{}", orderNumber);
		return StatusCodes.OK;
	}

}
