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
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.processor.validation.PredicateValidationException;
import org.apache.camel.util.jsse.SSLContextParameters;
import org.openfs.lanbilling.dreamkas.model.Receipt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import lb.api3.Api3PortType;

import static org.apache.camel.builder.PredicateBuilder.and;

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
				rest("/sber/callback")
					.bindingMode(RestBindingMode.off)
					.get()
						.param().name("status").dataType("integer").description("operation status").endParam()
						.param().name("mdOrder").dataType("string").description("receipt").endParam()
						.param().name("operation").dataType("string").description("opration").endParam()
						.param().name("orderNumber").dataType("string").description("orderNumber").endParam()
						.clientRequestValidation(true)
						.route().id("ProcessSberCallback")
							.setExchangePattern(ExchangePattern.InOnly)
							.process("sberCallback")
							.setHeader("dreamkasEnable",constant("{{dreamkas.enable"))
							.filter(header("dreamkas").isEqualTo("true"))
								.log("Call fiscalization")
							.end()
						.endRest();

				// form payment endpoint
				rest("/checkout")
					.enableCORS(true)
					.bindingMode(RestBindingMode.off)
					// validate
					.get().description("Process validate user agreement number")
						.route()
							.routeId("ProcessFormValidate")
							.process("formValidate")
						.endRest()
					// checkout
					.post().description("Process Form Checkout parameters")
						.param().name("uid").dataType("integer").description("user agreement").endParam()
						.param().name("amount").dataType("integer").description("amount").endParam()
						.clientRequestValidation(true)
						.route().id("ProcessFormCheckout")
							// handle validate exception
							.onException(PredicateValidationException.class)
								.handled(true)
								.log(LoggingLevel.ERROR, "Wrong query parameters")
								.setBody(constant("Wrong query parameters"))
								.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
							.end()
							// validate input parameters
							.validate(and(header("amount").isNotNull(), header("uid").isNotNull()))
							// process request to create LB prepayment record and get orderNumber
							.process("formCheckout")
							.filter(header("orderNumber").isNotNull())
								.process("sberRegisterOrder")
							.end()
						.endRest();

				// Dreamkas Webhook callback endpoint
				rest("/dreamkas")
					.bindingMode(RestBindingMode.off)
					.post()
						.route().id("ProcessDreamkasCallback")
						.unmarshal(formatMap)
						.process("dreamkasCallback")
					.endRest();

				// Sberbank Register Order 
				from("direct:sberRegisterOrder").id("SberRegisterOrder")
					.onException(Exception.class)
						.handled(true)
						.log(LoggingLevel.ERROR, "got exception: ${body}")
					.end()
					.to("undertow:{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
						// convert success json message to map
						.unmarshal(formatMap)
					.end()
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
						.log(LoggingLevel.ERROR,"got error response code:${header.CamelHttpResponseCode}. body:[${body}]")
					.end();
					

				// LanBilling SOAP service 
				from("direct:lbsoap").id("LBcoreSoapBackend")
					.onException(SOAPFaultException.class)
						.handled(true)
						.log(LoggingLevel.ERROR, "got exception:${body}")
					.end()
					.marshal(lbsoap)
					.setHeader(Exchange.HTTP_METHOD).constant("POST")
					.to("undertow:http://{{lbcore}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler")
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
						// convert error response to string
						.transform(xpath("//detail/text()", String.class))
					.end()
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
						// convert success soap message to pojo
						.unmarshal(lbsoap)
					.end();

				// Dreamkas Receipts
				from("direct:dreamkas").id("DreamkasReceiptsBackend").autoStartup("{{dreamkas.enable}}")
					.onException(Exception.class)
						.handled(true)
						.log(LoggingLevel.ERROR, "we got exception:${body}")
					.end()
					.marshal(formatReceipt)
					.log(LoggingLevel.DEBUG, "request:${body}")
					.to("undertow:https://kabinet.dreamkas.ru/api/receipts?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
					.unmarshal(formatMap);

			}
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
