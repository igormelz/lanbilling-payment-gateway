package org.openfs.lanbilling;

import java.util.Map;

import javax.xml.ws.soap.SOAPFaultException;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.fastjson.FastjsonDataFormat;
import org.apache.camel.dataformat.soap.SoapJaxbDataFormat;
import org.apache.camel.dataformat.soap.name.ServiceInterfaceStrategy;
import org.apache.camel.http.common.cookie.CookieHandler;
import org.apache.camel.http.common.cookie.InstanceCookieHandler;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.openfs.lanbilling.dreamkas.model.Receipt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import lb.api3.Api3PortType;

@SpringBootApplication
public class PaymentGatewayApplication {

	@Bean
	CookieHandler cookieHandler() {
		return new InstanceCookieHandler();
	}

	@Bean
	SSLContextParameters sslContext() {
//		KeyStoreParameters ksp = new KeyStoreParameters();
//		ksp.setResource("classpath:ssl/keystore.jks");
//		ksp.setPassword("keystorePassword");
//
//		KeyManagersParameters kmp = new KeyManagersParameters();
//		kmp.setKeyStore(ksp);
//		kmp.setKeyPassword("keyPassword");
// 		SSLContextParameters scp = 
		return new SSLContextParameters();
	}

	@Bean
	RouteBuilder lbpayRoutes() {
		return new RouteBuilder() {

			@Override
			public void configure() throws Exception {
				SoapJaxbDataFormat lbsoap = new SoapJaxbDataFormat("lb.api3",
						new ServiceInterfaceStrategy(Api3PortType.class, true));

				FastjsonDataFormat formatReceipt = new FastjsonDataFormat(Receipt.class);
				FastjsonDataFormat formatMap = new FastjsonDataFormat(Map.class);

				restConfiguration().component("undertow").host("localhost").port("{{port}}").contextPath("/pay")
						.dataFormatProperty("com.fasterxml.jackson.databind.SerializationFeature.disableFeatures",
								"WRITE_NULL_MAP_VALUES");

//				rest("/sber/online").bindingMode(RestBindingMode.xml).consumes("application/xml").produces("application/xml")
//						.get().outType(SberOnlineResponse.class).route().routeId("ProcessSberOnline").process("sberOnline")
//						.endRest();

				// SberBank Callback endpoint
				rest("/sber/callback").bindingMode(RestBindingMode.off).get().route().routeId("ProcessSberCallback")
						.setExchangePattern(ExchangePattern.InOnly).process("sberCallback").endRest();

				// form payment v1 endpoint
				rest("/checkout").enableCORS(true).bindingMode(RestBindingMode.off)
						// validate
						.get().to("direct:validate")
						// process
						.post().to("direct:checkout");

				// form payment v2 endpoint
				rest("/form").enableCORS(true).bindingMode(RestBindingMode.off)
						// validate form fields
						.get("/validate").to("direct:validate")
						// process from
						.post("/checkout").to("direct:checkout");

				// Dreamkas Webhook callback endpoint
				rest("/dreamkas").bindingMode(RestBindingMode.off).post().route().routeId("ProcessDreamkasCallback")
						.unmarshal().json(JsonLibrary.Fastjson, Map.class).process("dreamkasCallback").endRest();

				from("direct:validate").id("ProcessFormValidate").process("formValidate");

				from("direct:checkout").id("ProcessFormCheckout").process("formCheckout");

				// LanBilling SOAP backend service endpoint
				from("direct:lbsoap").id("LBcoreSoapBackend")

						// handling exception
						.onException(SOAPFaultException.class).handled(true).log(LoggingLevel.ERROR,"LBCORE EXCEPTION:${body}").end()

						// format SOAP message
						.marshal(lbsoap)

						// post to backend
						.setHeader(Exchange.HTTP_METHOD).constant("POST")
						.to("undertow:http://{{lbcore}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler")

						// convert error response to string
						.filter().simple("${header.CamelHttpResponseCode} != 200").transform()
						.xpath("//detail/text()", String.class).end()

						// convert success soap message to pojo
						.filter().simple("${header.CamelHttpResponseCode} == 200").unmarshal(lbsoap).end();

				// Sberbank acquiring backend service endpoint
				from("direct:sberbank").id("SberbankBackend")

						// set http headers
						.setHeader(Exchange.CONTENT_TYPE).constant("application/x-www-form-urlencoded")

						// post request to backend
						.setHeader(Exchange.HTTP_METHOD).constant("POST")
						.to("undertow:{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")

						// convert response to map
						.unmarshal(formatMap); //.json(JsonLibrary.Fastjson, Map.class);

				// Dreamkas backend Receipts endpoint
				from("direct:dreamkas").id("DreamkasReceiptsBackend")
						// convert to json
						.marshal(formatReceipt)
						.log(LoggingLevel.DEBUG, "request body:${body}")
						// post request to backend
						.to("undertow:https://kabinet.dreamkas.ru/api/receipts?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
						.unmarshal(formatMap); //.json(JsonLibrary.Fastjson, Map.class).log("RES:${body}");

			}
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
