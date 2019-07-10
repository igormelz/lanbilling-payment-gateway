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
import org.openfs.lanbilling.dreamkas.DreamkasLogError;
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
	RouteBuilder paymentGateway() {
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
						.route().id("SberCallback")
							//.setExchangePattern(ExchangePattern.InOnly)
							.onException(PredicateValidationException.class)
								.handled(true)
								.log(LoggingLevel.WARN, "${exception.message}")
								.setBody(constant(""))
								.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
							.end()
							.validate(and(header("status").isNotNull(),header("operation").isNotNull(),header("mdOrder").isNotNull(),header("orderNumber").isNotNull()))
							.validate(header("status").in("0","1"))
							.choice()
								.when(and(header("status").isEqualTo("1"),header("operation").isEqualTo("deposited")))
									//.setHeader(Exchange.HTTP_RESPONSE_CODE,method("sberCallback","processPayment"))
									.to("direct:doPayment")
								.when(and(header("status").isEqualTo("1"),header("operation").isEqualTo("approved")))
									.setHeader(Exchange.HTTP_RESPONSE_CODE,constant(200))
								.when(and(header("status").isEqualTo("1"),header("operation").in("refunded","reversed")))
									.setHeader(Exchange.HTTP_RESPONSE_CODE,method("sberCallback","processRefund"))
								.when(header("status").isEqualTo("0"))
									.setHeader(Exchange.HTTP_RESPONSE_CODE,method("sberCallback","cancelPrePayment"))
								.otherwise()
									.log(LoggingLevel.ERROR,"Receive unknown operation: ${headers}")
									.setHeader(Exchange.HTTP_RESPONSE_CODE,constant(200))
							.end()
						.endRest();

				// form payment endpoint
				rest("/checkout")
					.bindingMode(RestBindingMode.off)
					.get().enableCORS(true).description("Validate account (agreement number)")
						.route()
							.routeId("ProcessFormValidate")
							.onException(PredicateValidationException.class)
								.handled(true)
								.log(LoggingLevel.WARN, "uid:[${header.uid}]:${exception.message}")
								.setBody(constant(""))
								.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
							.end()
							.validate(and(header("uid").isNotNull(),header("uid").regex("\\d{6}")))
							.setHeader(Exchange.HTTP_RESPONSE_CODE,method("validateAccount"))
							.setBody(constant(""))
						.endRest()
					.post().description("Processing Form Checkout")
						.route().id("ProcessFormCheckout")
							.onException(PredicateValidationException.class)
								.handled(true)
								.log(LoggingLevel.WARN, "${exception.message}")
								.setBody(constant(""))
								.setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
							.end()
							.validate(and(header("amount").isNotNull(),header("amount").regex("^[1-9][0-9]{1,4}$")))
							.validate(and(header("uid").isNotNull(),header("uid").regex("^\\d{6}$")))
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
						.route().id("DreamkasCallback")
						.unmarshal(formatMap)
						.process("dreamkasCallback")
					.endRest();

				// Sberbank process payment 
				from("direct:doPayment").id("doPayment")
					// process payment on LB
					.bean("sberCallback","processPayment")
					// if payment success and return amount 
					.filter(and(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200),header("amount").isNotNull()))
						.setExchangePattern(ExchangePattern.InOnly)
						.bean("dreamkasReceipt","register")
					.end()
				.setBody(constant(""));
					
				
				// Sberbank Register Order 
				from("direct:sberRegisterOrder").id("SberRegisterOrder")
					.onException(Exception.class)
						.handled(true)
						.log(LoggingLevel.ERROR, "register failed with exception:${exception}")
					.end()
					.to("undertow:{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
						// convert success json message to map
						.unmarshal(formatMap)
					.end()
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
						.log(LoggingLevel.ERROR,"error response code:${header.CamelHttpResponseCode}. body:[${body}]")
					.end();
					

				// LanBilling SOAP service 
				from("direct:lbsoap").id("LBcoreSoapBackend")
					.onException(SOAPFaultException.class)
						.handled(true)
						.log(LoggingLevel.ERROR, "${exception.message}")
					.end()
					.marshal(lbsoap)
					.setHeader(Exchange.HTTP_METHOD).constant("POST")
					.to("undertow:http://{{lbcore}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler")
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isNotEqualTo(200))
						.transform(xpath("//detail/text()", String.class))
					.end()
					.filter(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(200))
						.unmarshal(lbsoap)
					.end();

				// Dreamkas API register receipt
				from("seda:dreamkasRegisterReceipt").id("DreamkasRegisterReceipt")
					.errorHandler(defaultErrorHandler()
							.useExponentialBackOff()
							.maximumRedeliveries(3)
							.redeliveryDelay(3000)
							.onExceptionOccurred(new DreamkasLogError())
							.asyncDelayedRedelivery()
							.useOriginalMessage())
					.marshal(formatReceipt)
					.log("Build receipt:${body}")
					.to("undertow:{{dreamkas.Url}}?sslContextParameters=#sslContext")
					.unmarshal(formatMap)
					.log("Success register receipt:${body[id]}, status:${body[status]}");

			}
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(PaymentGatewayApplication.class, args);
	}

}
