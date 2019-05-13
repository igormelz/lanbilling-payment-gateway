package org.openfs.lanbilling;

import org.apache.camel.http.common.cookie.CookieHandler;
import org.apache.camel.http.common.cookie.InstanceCookieHandler;
import org.apache.camel.util.jsse.KeyManagersParameters;
import org.apache.camel.util.jsse.KeyStoreParameters;
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
		KeyStoreParameters ksp = new KeyStoreParameters();
		ksp.setResource("classpath:keystore.jks");
		ksp.setPassword("keystorePassword");

		KeyManagersParameters kmp = new KeyManagersParameters();
		kmp.setKeyStore(ksp);
		kmp.setKeyPassword("keyPassword");

		SSLContextParameters scp = new SSLContextParameters();
		return scp;
	}
	
	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}

