package org.openfs.lanbilling.dreamkas;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component("dreamkasCallback")
public class DreamkasCallbackService implements Processor {

	/**
	 * Process webhook callback with following parameters: <br>
	 * <b>action</b> - enum of CREATE, UPDATE, DELETE<br>
	 * <b>type</b> - enum of PRODUCT, DEVICE, ENCASHMENT, SHIFT, RECEIPT,
	 * OPERATION<br>
	 * <b>data</b> - object as id (string),<br>
	 * name (string)<br>
	 * type enum of COUNTABLE, SCALABLE, ALCOHOL, CLOTHES, SHOES, SERVICE,
	 * TOBACCO,<br>
	 * departmentId (number),<br>
	 * quantity (number),<br>
	 * prices array of { deviceId (number), value (number) },<br>
	 * price ( number),<br>
	 * meta (enum),<br>
	 * barcodes (array of string),<br>
	 * vendorCodes - array of string,<br>
	 * tax - NDS_NO_TAX, NDS_0, NDS_10, NDS_20, NDS_10_CALCULATED,
	 * NDS_20_CALCULATED,<br>
	 * createdAt - iso string datetime,<br>
	 * updatedAt string iso string datetime
	 */
	@Override
	public void process(Exchange exchange) throws Exception {
		// TODO Auto-generated method stub
	}

}
