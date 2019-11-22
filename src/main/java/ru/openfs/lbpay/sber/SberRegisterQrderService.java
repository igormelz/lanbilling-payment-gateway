package ru.openfs.lbpay.sber;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("sberRegisterOrder")
@Profile("prom")
@Configuration
public class SberRegisterQrderService implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(SberRegisterQrderService.class);

	@EndpointInject(uri = "direct:sberRegisterOrder")
	ProducerTemplate producer;

	@Value("${sber.userName}")
	private String username;

	@Value("${sber.password}")
	private String password;

	@Value("${sber.token:none}")
	private String token;

	@Value("${sber.successUrl}")
	private String returnUrl;

	@Value("${sber.failUrl}")
	private String failUrl;

	/**
	 * create query string to register payment
	 * 
	 * @param orderNumber the lbcore prepayment id
	 * @param amount payment in roubles 
	 * @param account (agreement) number
	 * @return urlencoded query string
	 */
	private String createQueryString(long orderNumber, Double amount, String account) {
		StringBuilder queryString = new StringBuilder();
		if (token.equalsIgnoreCase("none")) {
			queryString.append("userName=").append(username).append("&password=").append(encodeValue(password));
		} else {
			queryString.append("token=").append(token);
		}
		queryString.append("&orderNumber=").append(String.valueOf(orderNumber));
		queryString.append("&amount=").append(String.valueOf(amount.intValue() * 100));
		queryString.append("&returnUrl=").append(encodeValue(returnUrl));
		queryString.append("&failUrl=").append(encodeValue(failUrl));
		queryString.append("&description=").append(account);
		queryString.append("&currency=643");
		queryString.append("&language=ru");
		queryString.append("&pageView=DESKTOP");
		queryString.append("&sessionTimeoutSecs=1200"); // 20 min
		return queryString.toString();
	}

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.setBody("");
		
		String queryString = createQueryString(message.getHeader("orderNumber", Long.class),
				message.getHeader("amount", Double.class), message.getHeader("uid", String.class));

		try {
			Object response = producer.requestBodyAndHeader(null, Exchange.HTTP_QUERY, queryString);			
			if (response != null && response instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, String> sberResponse = (Map<String, String>) response;
				if (sberResponse.containsKey("errorCode")) {
					// process error response
					LOG.error("Sberbank response error code:{}, message:{}", sberResponse.get("errorCode"),
							sberResponse.get("errorMessage"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_ACCEPTABLE);
					return;
				}
				if (sberResponse.containsKey("formUrl")) {
					// process success response
					LOG.info("Sberbank success register orderId:{}", sberResponse.get("orderId"));
					message.removeHeaders(".*");
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.MOVED_PERMANENTLY);
					message.setHeader("Location", sberResponse.get("formUrl"));
					return;
				}
			}
			// otherwise return error 
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_ACCEPTABLE);
		} catch (CamelExecutionException e) {
			LOG.error("Sber got exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}
	}

	private String encodeValue(String value) {
		try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

}
