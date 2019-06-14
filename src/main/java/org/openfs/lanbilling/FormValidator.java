package org.openfs.lanbilling;

import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.openfs.lanbilling.LbSoapService;
import org.openfs.lanbilling.LbSoapService.CodeExternType;
import org.openfs.lanbilling.LbSoapService.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;

@Component("validateAccount")
@Configuration
public class FormValidator {
	private static final Logger LOG = LoggerFactory.getLogger(FormValidator.class);

	@Autowired
	LbSoapService lbapi;

	/**
	 * validate UserId
	 * 
	 * @param uid - agreement number
	 * @return http status code: 202 - OK, 400 - validation error, 500 - server error
	 */
	@Handler
	public int validateUserId(@Header("uid") String uid) {
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

}
