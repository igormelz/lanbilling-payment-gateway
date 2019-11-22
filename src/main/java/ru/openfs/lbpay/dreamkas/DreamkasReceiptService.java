package ru.openfs.lbpay.dreamkas;

import java.util.HashMap;
import java.util.Map;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import ru.openfs.lbpay.dreamkas.model.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service("dreamkas")
@Profile("prom")
@Configuration
public class DreamkasReceiptService {
	private static final Logger LOG = LoggerFactory.getLogger(DreamkasReceiptService.class);

	@Value("${dreamkas.deviceid}")
	private int deviceId;

	@Value("${dreamkas.serviceName}")
	private String serviceName;

	@Value("${dreamkas.taxmode}")
	private String taxmode;

	@Value("${dreamkas.token}")
	private String token;

	@EndpointInject(uri = "seda:dreamkasRegisterReceipt")
	protected ProducerTemplate producer;

	/**
	 * Register receipt for success payment or refund
	 * 
	 * <pre>Create receipt for payment (refund) and send register request to api service</pre>
	 * 
	 * @param orderNumber the prepayment record_id
	 * @param mdOrder the card payment reference id
	 * @param amount the payment amount 
	 * @param phone the customer phone (E.164 format) 
	 * @param email the customer email 
	 * @param receiptType the receipt type SALE or REFUND
	 */
	@Handler
	public void register(@Header("orderNumber") long orderNumber, @Header("mdOrder") String mdOrder,
			@Header("amount") double amount, @Header("phone") String phone, @Header("email") String email,
			@Header("receiptType") String receiptType) {

		LOG.info("Processing register {} receipt order:{}, mdOrder:{}, amount:{}, phone:{}, email:{}", receiptType,
				orderNumber, mdOrder, amount, phone, email);

		// convert price to 
		long service_price = (long) (amount * 100);

		Receipt.Builder builder;
		if ("SALE".equalsIgnoreCase(receiptType)) {
			builder = Receipt.saleReceiptBuilder(deviceId, taxmode, mdOrder)
					.addNoTaxServicePosition(serviceName, service_price).addCardPayment(service_price);
		} else if ("REFUND".equalsIgnoreCase(receiptType)) {
			builder = Receipt.refundReceiptBuilder(deviceId, taxmode, mdOrder)
					.addNoTaxServicePosition(serviceName, service_price).addCardPayment(service_price);
		} else {
			LOG.error("Unknown receiptType:{}", receiptType);
			return;
		}

		// add attributes
		boolean attr = false;
		if (phone != null && !phone.isEmpty() && phone.matches("^\\+?[1-9]\\d{10,13}+$")) {
			// fix leading plus
			builder.addPhoneAttribute((phone.startsWith("+")) ? phone : "+" + phone);
			LOG.debug("Add receipt attr phone:{}", phone);
			attr = true;
		}

		if (email != null && !email.isEmpty()
				&& email.matches("^([a-zA-Z0-9_\\-\\.]+)@([a-zA-Z0-9_\\-\\.]+)\\.([a-zA-Z]{2,5})$")) {
			builder.addEmailAttribute(email);
			LOG.debug("Add receipt attr email:{}", email);
			attr = true;
		}

		if (!attr) {
			LOG.error("Fail build receipt order:{} - phone or email is not defined", orderNumber);
			return;
		}

		// register receipt
		Map<String, Object> headers = new HashMap<String, Object>(4);
		headers.put("Content-Type", "application/json");
		headers.put("Content-Encoding", "utf-8");
		headers.put("Authorization", "Bearer " + token);
		headers.put("CamelHttpMethod", "POST");
		try {
			producer.sendBodyAndHeaders(builder.buildReceipt(), headers);
		} catch (CamelExecutionException e) {
			LOG.error("Fail register receipt order:{} - {}", orderNumber, e.getMessage());
		}
	}

}
