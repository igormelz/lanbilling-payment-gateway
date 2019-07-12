package org.openfs.lanbilling.dreamkas;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import org.openfs.lanbilling.dreamkas.model.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

@Service("dreamkasReceipt")
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
	ProducerTemplate producer;

	@Handler
	public void register(@Header("orderNumber") long orderNumber, @Header("amount") double amount, @Header("phone") String phone, @Header("email") String email)  {
		LOG.info("Processing register receipt order:{}, service:[{}], amount:{}, phone:{}, email:{}", orderNumber,
				serviceName, amount, phone, email);

		long service_price = (long) (amount * 100);
		// create receipt request with no_tax and card payment
		Receipt.Builder builder = Receipt.builder(deviceId, taxmode).addNoTaxServicePosition(serviceName, service_price)
				.addCardPayment(service_price);
		
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
		Map<String,Object> headers = new HashMap<String,Object>(4);
		headers.put("Content-Type", "application/json");
		headers.put("Content-Encoding", "utf-8");
		headers.put("Authorization", "Bearer " + token);
		headers.put("CamelHttpMethod", "POST");
		try {
			producer.sendBodyAndHeaders(builder.buildSaleReceipt(),headers);			
		} catch (CamelExecutionException e) {
			LOG.error("Fail register receipt order:{} - {}", orderNumber, e.getMessage());
		}
	}

}
