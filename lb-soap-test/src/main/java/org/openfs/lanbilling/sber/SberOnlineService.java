package org.openfs.lanbilling.sber;

import java.util.Map;
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
import org.springframework.stereotype.Component;

import lb.api3.PaymentResponse;

@Component("sberOnline")
public class SberOnlineService implements Processor {
	private static final Logger log = LoggerFactory.getLogger(SberOnlineService.class);
	private static Pattern PAY_ID_PATTERN = Pattern.compile("\\d+");
	private static Pattern PAY_DATE_PATTERN = Pattern.compile("(\\d{2}).(\\d{2}).(\\d{4})_(\\d{2}):(\\d{2}):(\\d{2})");
	private static Pattern AMOUNT_PATTERN = Pattern.compile("\\d+\\.\\d{0,2}");

	@Autowired
	LbSoapService lbapi;

	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();

		message.removeHeaders("Camel*");

		// validate action
		if (message.getHeader("ACTION") == null) {
			log.error("Parameter [ACTION] not found");
			message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_ACTION));
			return;
		}

		// validate account parameter
		if (message.getHeader("ACCOUNT") == null) {
			log.error("Parameter [ACCOUNT] not found");
			message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_WRONG_FORMAT));
			return;
		}

		// process action
		if (message.getHeader("ACTION", String.class).equalsIgnoreCase("check")) {
			// process check action
			message.setBody(checkAccount(message.getHeader("ACCOUNT", String.class)));
			return;
		}

		if (message.getHeader("ACTION", String.class).equalsIgnoreCase("payment")) {
			// validate parameters
			if (message.getHeader("AMOUNT") == null
					|| !AMOUNT_PATTERN.matcher(message.getHeader("AMOUNT", String.class)).matches()) {
				log.error("Parameter [AMOUNT] not found or bad formatted");
				message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.PAY_AMOUNT_ERROR));
				return;
			}

			if (message.getHeader("PAY_ID") == null
					|| !PAY_ID_PATTERN.matcher(message.getHeader("PAY_ID", String.class)).matches()) {
				log.error("Parameter [PAY_ID] not found or has bad format");
				message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_TRX_FORMAT));
				return;
			}

			if (message.getHeader("PAY_DATE") == null
					|| !PAY_DATE_PATTERN.matcher(message.getHeader("PAY_DATE", String.class)).matches()) {
				log.error("Parameter [PAY_DATE] not found or bad formatted");
				message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_FORMAT_DATE));
				return;
			}

			message.setBody(processPayment(message.getHeader("ACCOUNT", String.class),
					message.getHeader("AMOUNT", String.class), message.getHeader("PAY_ID", String.class),
					message.getHeader("PAY_DATE", String.class)));
			return;
		}

		// unknown action
		log.warn("Parameter [ACTION] has incorrect value:{}", message.getHeader("ACTION", String.class));
		message.setBody(new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_ACTION));
		return;

	}

	protected SberOnlineResponse checkAccount(String account) {
		log.info("Try to check account:{}", account);

		// connect to LB
		if (!lbapi.connect()) {
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.BACKEND_ERR);
		}

		// call service for account
		//ServiceResponse response = lbapi.getAccountByAgreementNumber(account);
		ServiceResponse response = lbapi.getAccount(CodeExternType.AGRM_NUM, account);

		// disconnect
		lbapi.disconnect();

		// process error response
		if (!response.isSuccess() && !response.isFault()) {
			log.error("Check account has no response");
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.BACKEND_ERR);
		}

		// process fault
		if (response.isFault()) {
			String errorStr = (String) response.getBody();
			if (errorStr.matches(".*not found.*")) {
				log.warn("Check account:{} not found:{}", account, errorStr);
				return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND);
			}
			log.error("Check account:{} has fault:{}", account, errorStr);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.BACKEND_ERR);
		}

		// process success response
		Map<String, Object> values = response.getValues();
		SberOnlineResponse ret = new SberOnlineResponse(SberOnlineResponse.CodeResponse.OK);
		ret.setFio(values.get("name").toString());
		log.info("Check account:{} success, FIO:{}", account, values.get("name").toString());
		return ret;
	}

	protected SberOnlineResponse processPayment(String account, String amount, String pay_id, String pay_date) {
		log.info("Try to payment account:{}, amount:{}, pay_id:{}, pay_date:{}", account, amount, pay_id, pay_date);

		// connect to LB
		if (!lbapi.connect()) {
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.BACKEND_ERR);
		}
		
		// check account 
		ServiceResponse response = lbapi.getAccountByAgreementNumber(account);
		if (!response.isSuccess()) {
			log.error("Account not found:{}",account);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND);
		}
		// process account response 
		long acctid = (long) response.getValues().get("uid");
		log.info("Found UID:{} for account:{}", acctid, account);

		// get agreement id
		response = lbapi.getAgreementId(acctid);
		if (!response.isSuccess()) {
			log.error("Aggrement not found for account:{}", account);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND);
		}
		long argmid = (long) response.getValues().get("agrmid");
		log.info("Found argmid:{} for account:{}", argmid, account);

		// do payment 
		lbapi.doPayment(pay_id, Double.parseDouble(amount), argmid);
		if (!response.isSuccess()) {
			if (response.isFault()) {
				log.warn("doPayment return error:{}", response.getBody());
			} else {
				log.error("doPayment has no response");
			}
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.BACKEND_ERR);
		}
		log.info("Payment success:{}", ((PaymentResponse) response.getBody()).getRet());
		SberOnlineResponse ret = new SberOnlineResponse(SberOnlineResponse.CodeResponse.OK);
		return ret;
	}

}
