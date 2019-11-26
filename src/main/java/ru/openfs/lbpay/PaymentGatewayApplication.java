package ru.openfs.lbpay;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.http.common.cookie.CookieHandler;
import org.apache.camel.http.common.cookie.InstanceCookieHandler;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PaymentGatewayApplication {

	@Bean
	CookieHandler cookieHandler() {
		return new InstanceCookieHandler();
	}

	@Bean
	SSLContextParameters sslContext() {
		return new SSLContextParameters();
	}

	@Bean
	RouteBuilder restConfig() {
		return new RouteBuilder() {

			@Override
			public void configure() throws Exception {

				restConfiguration().component("undertow").host("localhost").port("{{port}}").contextPath("/pay");
				//.dataFormatProperty("com.fasterxml.jackson.databind.SerializationFeature.disableFeatures","WRITE_NULL_MAP_VALUES");

			}

		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
