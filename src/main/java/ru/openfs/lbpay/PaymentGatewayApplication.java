package ru.openfs.lbpay;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.jsse.SSLContextClientParameters;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PaymentGatewayApplication {

	// @Bean
	// CookieHandler cookieHandler() {
	// 	return new InstanceCookieHandler();
	// }

	@Bean
	SSLContextClientParameters sslContext() {
		return new SSLContextClientParameters();
	}

	@Bean
	RouteBuilder restConfig() {
		return new RouteBuilder() {

			@Override
			public void configure() throws Exception {
				restConfiguration().component("undertow").host("localhost").port("{{port}}").contextPath("/pay");
			}

		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
