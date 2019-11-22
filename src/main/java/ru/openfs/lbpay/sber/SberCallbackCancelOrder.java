package ru.openfs.lbpay.sber;

import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.undertow.util.StatusCodes;
import ru.openfs.lbpay.lbsoap.LbSoapService;

@Component("cancelOrder")
@Configuration
@Profile("prom")
public class SberCallbackCancelOrder {

    private static Logger LOG = LoggerFactory.getLogger(SberCallbackCancelOrder.class);

    @Autowired
    protected LbSoapService lbapi;

    /**
	 * cancel prepayment order
	 */
	@Handler
	public int cancelOrder(@Header("orderNumber") Long orderNumber, @Header("operation") String operation) {
		LOG.info("Processing cancel orderNumber:{} by operation:{}", orderNumber, operation);
		
		// workaround for cancel deposited. Logging and wait to success 
		if (operation.equalsIgnoreCase("deposited")) {
			LOG.warn("Do not cancel orderNumber:{} by operation:deposited. Waiting to success.", orderNumber);
			return StatusCodes.OK;
		}

		// process lbcore cancel prepayment
		return lbapi.processCancelPrePayment(orderNumber);
	}

}