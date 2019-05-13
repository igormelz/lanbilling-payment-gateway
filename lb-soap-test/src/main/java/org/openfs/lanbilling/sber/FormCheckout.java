package org.openfs.lanbilling.sber;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Component("formCheckout")
@Configuration
public class FormCheckout {
	private static final Logger LOG = LoggerFactory.getLogger(FormCheckout.class);
	private static Pattern NUMBERS = Pattern.compile("\\d+");

	@Autowired
	LbSoapService lbapi;

	@EndpointInject(uri = "direct:sberbank")
	ProducerTemplate producer;

	@Value("${sber.userName}")
	private String userName;

	@Value("${sber.password}")
	private String password;

	@Value("${sber.successUrl}")
	private String successUrl;

	@Value("${sber.failUrl}")
	private String failUrl;

	/**
	 * validate account
	 * 
	 * @param exchange
	 * @throws Exception
	 */
	@Handler
	public void validate(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// validate params
		if (message.getHeader("uid") != null && !message.getHeader("uid", String.class).isEmpty()) {
			LOG.info("Process verify UID:{}", message.getHeader("uid"));

			final String account = message.getHeader("uid", String.class);

			// validate format
			if (!NUMBERS.matcher(account).matches()) {
				LOG.warn("UID:{} has bad format", account);
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
				return;
			}

			// connect to LB
			if (!lbapi.connect()) {
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
				return;
			}

			ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
			lbapi.disconnect();

			// process error response
			if (response.isSuccess()) {
				LOG.info("UID:{} is success", account);
				return;
			}

			LOG.warn("UID:{} not found", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
			return;
		}

		// process request and set response code
		LOG.error("Unknown params");
		message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
	}

	@Handler
	public void checkout(Exchange exchange) {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// validate parameters
		if (message.getHeader("uid") == null || message.getHeader("amount") == null) {
			LOG.error("Unknown checkout params");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
			return;
		}

		final String account = message.getHeader("uid", String.class);
		final Double amount = message.getHeader("amount", Double.class);
		LOG.info("Process checkout for UID:{} on amount:{}", account, amount);

		// connect to LB
		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 503);
			return;
		}

		// verify account
		ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
		if (!response.isSuccess() || response.getValue(account) == null) {
			LOG.error("UID:{} not found", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
			lbapi.disconnect();
			return;
		}

		// insert prepayment record
		response = lbapi.insertPrePayment((long) response.getValue(account), amount);
		lbapi.disconnect();
		if (!response.isSuccess()) {
			LOG.error("Insert PrePayment error for UID:{}", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
			return;
		}

		final long orderNumber = (long) response.getValue("orderNumber");
		LOG.info("Insert PrePayment orderNumber:{} on amount:{} for UID:{}", orderNumber, amount, account);

		// prep query
		StringBuilder sb = new StringBuilder();
		sb.append("userName=").append(userName);
		sb.append("&password=").append(password);
		sb.append("&currency=").append("643");
		sb.append("&language=ru");
		sb.append("&pageView=DESKTOP");
		sb.append("&sessionTimeoutSecs=300");
		sb.append("&amount=").append(amount.intValue() * 100);
		sb.append("&returnUrl=").append(successUrl);
		sb.append("&failUrl=").append(failUrl);
		sb.append("&orderNumber=").append(orderNumber);
		// sb.append("&clientId=").append(account);
		// sb.append("&jsonParams={\"orderNumber\":").append(orderNumber).append(",\"description\":\"Provider
		// service\"}");
		LOG.info("Call Sber with query: {}", sb.toString());
		try {
			Object answer = producer.requestBodyAndHeader(null, Exchange.HTTP_QUERY,
					URLEncoder.encode(sb.toString(), "UTF-8"));
			if (answer == null) {
				LOG.error("Call to Sber has no response");
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
				return;
			}
			if (answer instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, String> sberResponse = (Map<String, String>) answer;
				// process if error
				if (sberResponse.containsKey("errorCode")) {
					LOG.error("Sber return error:{}", sberResponse.get("errorMessage"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
					return;
				}
				if (sberResponse.containsKey("formUrl")) {
					LOG.info("Sber return success orderId:{}", sberResponse.get("orderId"));
					LOG.info("Reroute client to url:{}", sberResponse.get("formUrl"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, 301);
					message.setHeader("Location", sberResponse.get("formUrl"));
				}
			}
		} catch (CamelExecutionException e) {
			LOG.error("Call to Sber got exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
			return;
		} catch (UnsupportedEncodingException e) {
			LOG.error("Call to Sber got URL exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
			return;
		}

	}
}
