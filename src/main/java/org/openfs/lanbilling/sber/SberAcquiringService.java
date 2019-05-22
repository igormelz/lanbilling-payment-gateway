package org.openfs.lanbilling.sber;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.undertow.util.StatusCodes;

@Service
@Configuration
public class SberAcquiringService {
	private static final Logger LOG = LoggerFactory.getLogger(SberAcquiringService.class);

	@EndpointInject(uri = "direct:sberbank")
	ProducerTemplate producer;

	@Value("${sber.username}")
	private String username;

	@Value("${sber.password}")
	private String password;

	@Value("${sber.successUrl}")
	private String successUrl;

	@Value("${sber.failUrl}")
	private String failUrl;

	public void callService(long orderNumber, Double amount, Message message) {
		LOG.info("Processing sberbank acquiring for orderNumber:{}, amount:{}", orderNumber, amount);

		// define query string
		StringBuilder sb = new StringBuilder();
		sb.append("userName=").append(username);
		sb.append("&password=").append(password);
		sb.append("&currency=643").append("&language=ru").append("&pageView=DESKTOP").append("&sessionTimeoutSecs=300");
		sb.append("&amount=").append(amount.intValue() * 100);
		sb.append("&returnUrl=").append(successUrl);
		sb.append("&failUrl=").append(failUrl);
		sb.append("&orderNumber=").append(orderNumber);

		try {
			Object answer = producer.requestBodyAndHeader(null, Exchange.HTTP_QUERY,
					URLEncoder.encode(sb.toString(), "UTF-8"));
			if (answer == null) {
				LOG.error("Sber has no response");
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			} else if (answer instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, String> sberResponse = (Map<String, String>) answer;
				if (sberResponse.containsKey("errorCode")) {
					// process error response
					LOG.error("Sber return error:{}", sberResponse.get("errorMessage"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
				} else if (sberResponse.containsKey("formUrl")) {
					// process success response
					LOG.info("Sber return success orderId:{}", sberResponse.get("orderId"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.MOVED_PERMANENTLY);
					LOG.info("Redirected client to url:{}", sberResponse.get("formUrl"));
					message.setHeader("Location", sberResponse.get("formUrl"));
				}
			}
		} catch (CamelExecutionException e) {
			LOG.error("Sber got exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
		} catch (UnsupportedEncodingException e) {
			LOG.error("Sber got URL exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
		}
	}
}
