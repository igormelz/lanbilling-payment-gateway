package ru.openfs.lbpay.formcheckout;

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prom")
public class FormAgreementValidator implements Predicate {

	@Autowired
	LbSoapService lbapi;

	@Override
	public boolean matches(Exchange exchange) {
		return lbapi
				.isActiveAgreement(exchange.getIn().getHeader(PaymentGatewayConstants.FORM_AGREEMENT, String.class));
	}

}
