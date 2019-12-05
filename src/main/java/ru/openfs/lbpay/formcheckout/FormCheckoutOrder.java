package ru.openfs.lbpay.formcheckout;

import org.apache.camel.Handler;
import org.apache.camel.Header;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.LbSoapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("checkout")
@Profile("prom")
public class FormCheckoutOrder {

	@Autowired
	LbSoapService lbapi;

	@Handler
	public long getOrderNumber(@Header(PaymentGatewayConstants.FORM_AGREEMENT) String agreement,
			@Header(PaymentGatewayConstants.FORM_AMOUNT) Double amount) {
		return lbapi.createPrePaymentOrder(agreement, amount);
	}
}
