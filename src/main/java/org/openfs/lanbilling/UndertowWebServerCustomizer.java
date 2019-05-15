package org.openfs.lanbilling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.RequestLimitingHandler;

@Component
public class UndertowWebServerCustomizer implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {
	
	private static final Logger LOG = LoggerFactory.getLogger(UndertowWebServerCustomizer.class);
	
	@Value("${server.rateLimit:3}")
	private int rateLimit;
	
	@Override
	public void customize(UndertowServletWebServerFactory factory) {
		LOG.info("Starting customize Undertow");
		factory.addDeploymentInfoCustomizers(deploymentInfo -> deploymentInfo.addInitialHandlerChainWrapper(new HandlerWrapper() {

			@Override
			public HttpHandler wrap(HttpHandler handler) {
				LOG.info("Undertow set rateLimit:{}", rateLimit);
				return new RequestLimitingHandler(rateLimit, handler);
			}
			
		}));
	}

}
