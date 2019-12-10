package ru.openfs.lbpay;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PaymentGatewayApplication {

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
			}

		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
