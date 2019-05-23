package org.openfs.lanbilling.dreamkas;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.openfs.lanbilling.dreamkas.model.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;

import io.undertow.util.StatusCodes;

@Component("dreamkasCallback")
public class DreamkasCallbackService implements Processor {
	private static final Logger LOG = LoggerFactory.getLogger(DreamkasCallbackService.class);
	private static final String TYPE_PRODUCT = "PRODUCT";
	private static final String TYPE_SHIFT = "SHIFT";
	private static final String TYPE_RECEIPT = "RECEIPT";
	private static final String TYPE_DEVICE = "DEVICE";
	private static final String TYPE_OPERATION = "OPERATION";
	private static final String TYPE_ENCASHMENT = "ENCASHMENT";

	/**
	 * Process webhook callback.
	 */
	@Override
	public void process(Exchange exchange) throws Exception {
		Message message = exchange.getIn();
		message.removeHeaders("Camel.*");
		@SuppressWarnings("unchecked")
		Map<String, Object> body = message.getBody(Map.class);
		LOG.info("Processing callback action:{}, type:{}", body.get("action"), body.get("type"));

		if (body.get("type").toString().equalsIgnoreCase(TYPE_OPERATION)) {
			Operation operation = JSON.parseObject(body.get("data").toString(), Operation.class);
			if (operation.getStatus().equalsIgnoreCase(Operation.ERROR) && operation.getData() != null) {
				LOG.error("Receipt id:{} op_type:{} -- {}", operation.getId(), operation.getType(),
						operation.getData().getError().getCode());
			} else {
				LOG.info("Receipt id:{}, op_type:{}, status:{}", operation.getId(), operation.getType(),
						operation.getStatus());
			}
		} else {
			LOG.info("Receive unparsing data:{}", body.get("data"));
		}

		// response OK with null body
		message.setBody(null);
		message.setHeader(Exchange.HTTP_RESPONSE_CODE, StatusCodes.ACCEPTED);
	}

}
