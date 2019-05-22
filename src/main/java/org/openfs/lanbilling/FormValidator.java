package org.openfs.lanbilling;

import java.util.regex.Pattern;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("formValidate")
@Configuration
public class FormValidator implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(FormValidator.class);
	private static Pattern NUMBERS = Pattern.compile("\\d+");

	@Autowired
	LbSoapService lbapi;

	/**
	 * validate UserId
	 * 
	 * @param uid - agreement number
	 * @return http status code: 202 - OK, 400 - validation error, 500 - server error
	 */
	protected int validateUserId(String uid) {
		// validate format
		if (!NUMBERS.matcher(uid).matches()) {
			LOG.error("uid:{} has bad format", uid);
			return StatusCodes.BAD_REQUEST;
		}

		if (!lbapi.connect()) {
			return StatusCodes.INTERNAL_SERVER_ERROR;
		}

		ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, uid);
		lbapi.disconnect();

		if (response.isSuccess()) {
			LOG.info("uid:{} is success", uid);
			return StatusCodes.ACCEPTED;
		}

		LOG.warn("uid:{} not found", uid);
		return StatusCodes.BAD_REQUEST;
	}

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel*");

		// userid
		if (message.getHeader("uid") != null && !message.getHeader("uid", String.class).isEmpty()) {
			LOG.info("Process validate uid:{}", message.getHeader("uid"));
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, validateUserId(message.getHeader("uid", String.class)));
		} else {
			LOG.error("Unknown params");
			message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.BAD_REQUEST);
		}
	}

}
