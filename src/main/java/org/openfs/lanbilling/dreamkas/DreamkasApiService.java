package org.openfs.lanbilling.dreamkas;

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
public class DreamkasApiService {
	private static final Logger LOG = LoggerFactory.getLogger(DreamkasApiService.class);

	@Value("${dreamkas.deviceid}")
	private int deviceId;

	@Value("${dreamkas.taxmode}")
	private String taxmode;

	@EndpointInject(uri = "direct:dreamkas")
	ProducerTemplate producer;

	public DreamkasApiService() {
	}

	@SuppressWarnings("unchecked")
	public void fiscalization(final String serviceName, Long amount, String phone, String email) {
		LOG.info("Processing fiscalization service:{}, amount:{}, phone:{}, email:{}", serviceName, amount, phone,
				email);
		// create receipt
		Receipt.Builder builder = Receipt.builder(deviceId, taxmode).addNoTaxServicePosition(serviceName, amount);
		if (phone != null && !phone.isEmpty()) {
			builder.addPhoneAttribute(phone);
		}
		if (email != null && !email.isEmpty()) {
			builder.addEmailAttribute(email);
		}
		// call api
		try {
			Object response = producer.requestBody(builder.buildSaleReceipt());
			if (response == null) {
				LOG.error("Call Api has no response");
				return;
			}
			if (response instanceof Map) {
				Map<String,Object> answer = (Map<String,Object>)response;
				LOG.info("Fiscalization submitted. id:{}, status:{}", answer.get("id"), answer.get("status"));
			}
			
		} catch (CamelExecutionException e) {
			LOG.error("Call Api got exception:{}", e.getMessage());
			return;
		}
	}

}
