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
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("formCheckout")
@Configuration
public class FormCheckout {
	private static final Logger LOG = LoggerFactory.getLogger(FormCheckout.class);
	private static final Marker EMAIL_ALERT = MarkerFactory.getMarker("EMAIL_ALERT");
	private static Pattern NUMBERS = Pattern.compile("\\d+");

	@Autowired
	LbSoapService lbapi;

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

	@Handler
	public void validate(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");
		message.setBody("{}");
		
		// validate params
		if (message.getHeader("uid") != null && !message.getHeader("uid", String.class).isEmpty()) {
			LOG.info("Process verify uid:{}", message.getHeader("uid"));

			final String account = message.getHeader("uid", String.class);

			// validate format
			if (!NUMBERS.matcher(account).matches()) {
				LOG.error("UID:{} has bad format", account);
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
				return;
			}

			// connect to LB
			if (!lbapi.connect()) {
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
				return;
			}

			ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
			lbapi.disconnect();

			// process error response
			if (response.isSuccess()) {
				LOG.info("uid:{} is success", account);
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.OK);
				return;
			}

			LOG.warn("uid:{} not found", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
			return;
		}

		// process request and set response code
		LOG.error("Unknown params");
		message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_ACCEPTABLE);
	}

	@Handler
	public void checkout(Exchange exchange) {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");
		message.setBody("{}");
		
		// validate parameters
		if (message.getHeader("uid") == null || message.getHeader("amount") == null) {
			LOG.error("Unknown checkout parameters");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_ACCEPTABLE);
			return;
		}

		final String account = message.getHeader("uid", String.class);
		final Double amount = message.getHeader("amount", Double.class);
		LOG.info("Processing checkout uid:{}, amount:{}", account, amount);

		// connect to LB
		if (!lbapi.connect()) {
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}

		// verify account
		ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);
		if (!response.isSuccess() || response.getValue(account) == null) {
			LOG.warn("uid:{} not found", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.NOT_FOUND);
			lbapi.disconnect();
			return;
		}

		// insert prepayment record
		response = lbapi.insertPrePayment(response.getLong(account), amount);
		lbapi.disconnect();
		if (!response.isSuccess()) {
			LOG.error("Insert prepayment error for uid:{}", account);
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}

		final long orderNumber = response.getLong(LbSoapService.FIELD_ORDER_NUMBER);
		LOG.info("Create prepayment orderNumber:{}, amount:{}, uid:{}", orderNumber, amount, account);

		// Processing sberbank acquiring
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
				LOG.error(EMAIL_ALERT, "Call to Sber has no response");
				message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
				return;
			}
			if (answer instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, String> sberResponse = (Map<String, String>) answer;
				// process if error
				if (sberResponse.containsKey("errorCode")) {
					LOG.error(EMAIL_ALERT, "Sber return error:{}", sberResponse.get("errorMessage"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
					return;
				}
				// redirected to payment form
				if (sberResponse.containsKey("formUrl")) {
					LOG.info("Sber return success orderId:{}", sberResponse.get("orderId"));
					LOG.info("Redirected client to url:{}", sberResponse.get("formUrl"));
					message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.MOVED_PERMANENTLY);
					message.setHeader("Location", sberResponse.get("formUrl"));
				}
			}
		} catch (CamelExecutionException e) {
			LOG.error("Call to Sber got exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		} catch (UnsupportedEncodingException e) {
			LOG.error("Call to Sber got URL exception:{}", e.getMessage());
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.INTERNAL_SERVER_ERROR);
			return;
		}
	}
}
