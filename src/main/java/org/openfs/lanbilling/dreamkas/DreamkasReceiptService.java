package org.openfs.lanbilling.dreamkas;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.openfs.lanbilling.dreamkas.model.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

@Service
@Configuration
public class DreamkasReceiptService {
	private static final Logger LOG = LoggerFactory.getLogger(DreamkasReceiptService.class);

	@Value("${dreamkas.enable:false}")
	private boolean isEnable;
	
	@Value("${dreamkas.deviceid}")
	private int deviceId;

	@Value("${dreamkas.taxmode}")
	private String taxmode;

	@Value("${dreamkas.token}")
	private String token;

	@EndpointInject(uri = "direct:dreamkas")
	ProducerTemplate producer;

	public DreamkasReceiptService() {
	}

	@SuppressWarnings("unchecked")
	public void fiscalization(final String serviceName, Long amount, String phone, String email) {
		
		if (!isEnable) {
			return;
		}
	
		LOG.info("Processing fiscalization service:{}, amount:{}, phone:{}, email:{}", serviceName, amount, phone,
				email);
		// create receipt request with no_tax and card payment
		Receipt.Builder builder = Receipt.builder(deviceId, taxmode).addNoTaxServicePosition(serviceName, amount)
				.addCardPayment(amount);
		if (phone != null && !phone.isEmpty() && phone.matches("^\\+?[1-9]\\d{10,13}+$")) {
			// fix leading plus
			builder.addPhoneAttribute((phone.startsWith("+")) ? phone : "+" + phone);
			LOG.info("receipt using phone:{}", phone);
		}
		if (email != null && !email.isEmpty()
				&& email.matches("^([a-zA-Z0-9_\\-\\.]+)@([a-zA-Z0-9_\\-\\.]+)\\.([a-zA-Z]{2,5})$")) {
			builder.addEmailAttribute(email);
			LOG.info("receipt using email:{}", email);
		}
		// call api
		try {
			Map<String, Object> requestHeaders = new HashMap<>();
			requestHeaders.put("Content-Type", "application/json");
			requestHeaders.put("Content-Encoding", "utf-8");
			requestHeaders.put("Authorization", "Bearer " + token);
			requestHeaders.put("CamelHttpMethod", "POST");
			Object response = producer.requestBodyAndHeaders(builder.buildSaleReceipt(), requestHeaders);
			if (response == null) {
				LOG.error("Call to dreamkas has no response");
			} else if (response instanceof Map) {
				Map<String, Object> answer = (Map<String, Object>) response;
				if (answer.containsKey("status") && !answer.containsKey("id")) {
					LOG.error("Return error status:{}, message:{}", answer.get("status"), answer.get("message"));
				} else {
					LOG.info("Receipt accepted to fiscalization: id:{}, status:{}", answer.get("id"),
							answer.get("status"));
				}
			} else {
				LOG.error("Receive unknown answer");
			}
		} catch (CamelExecutionException e) {
			LOG.error("Call Api got exception:{}", e.getMessage());
			return;
		}
	}

}
