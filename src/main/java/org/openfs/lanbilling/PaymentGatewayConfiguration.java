package org.openfs.lanbilling;

import java.util.Map;

import javax.xml.ws.soap.SOAPFaultException;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.soap.SoapJaxbDataFormat;
import org.apache.camel.dataformat.soap.name.ServiceInterfaceStrategy;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.rest.RestBindingMode;
import org.apache.camel.model.rest.RestParamType;
import org.openfs.lanbilling.sber.SberOnlineResponse;
import org.springframework.context.annotation.Configuration;
import lb.api3.Api3PortType;

@Configuration
public class PaymentGatewayConfiguration extends RouteBuilder {

	@Override
	public void configure() throws Exception {

		SoapJaxbDataFormat lbsoap = new SoapJaxbDataFormat("lb.api3",
				new ServiceInterfaceStrategy(Api3PortType.class, true));

		// frontend
		restConfiguration().component("servlet").contextPath("/pay").dataFormatProperty(
				"com.fasterxml.jackson.databind.SerializationFeature.disableFeatures", "WRITE_NULL_MAP_VALUES");

		rest("/sber/online").bindingMode(RestBindingMode.xml).consumes("application/xml").produces("application/xml")
				.get().id("SberOnlineRestApi").param().name("ACTION").type(RestParamType.query).dataType("string")
				.endParam().param().name("ACCOUNT").type(RestParamType.query).dataType("string").endParam().param()
				.name("AMOUNT").type(RestParamType.query).dataType("string").endParam().param().name("PAY_ID")
				.type(RestParamType.query).dataType("string").endParam().param().name("PAY_DATE")
				.type(RestParamType.query).dataType("string").endParam().outType(SberOnlineResponse.class)
				.to("direct:processOnline");

		rest("/sber/callback").bindingMode(RestBindingMode.off).get().id("SberCallback").param().name("mdOrder")
				.type(RestParamType.query).dataType("string").endParam().param().name("orderNumber")
				.type(RestParamType.query).dataType("string").endParam().param().name("checksum")
				.type(RestParamType.query).dataType("string").endParam().param().name("operation")
				.type(RestParamType.query).dataType("string").endParam().param().name("status")
				.type(RestParamType.query).dataType("integer").endParam().to("direct:processCallback");

		rest("/checkout").enableCORS(true).bindingMode(RestBindingMode.off).produces("application/json")
				.id("FormCheckout").get().description("validate account").param().name("uid").type(RestParamType.query)
				.dataType("string").endParam().param().name("phone").type(RestParamType.query).dataType("string")
				.endParam().param().name("email").type(RestParamType.query).dataType("string").endParam().route()
				.routeId("ProcessFormCheckout").log("REQ: ${headers}").bean("formCheckout", "validate").endRest().post()
				.description("checkout prepayment").param().name("uid").dataType("string").endParam().param()
				.name("amount").dataType("string").endParam().route().routeId("ProcessPrePayment")
				.log("REQ: ${headers}").bean("formCheckout", "checkout").endRest();

		// service process online api
		from("direct:processOnline").id("ProcessOnline").log("REQ: ${headers}").process("sberOnline");

		// service process rest callback api
		from("direct:processCallback").id("ProcessCallback").log("REQ: ${headers}")
				.setExchangePattern(ExchangePattern.InOnly).process("sberCallback");

		// SOAP backend
		from("direct:lbsoap").id("LanBillingSoapBackend").onException(SOAPFaultException.class).handled(true)
				.log("EXCEPTION:${body}").end().marshal(lbsoap).setHeader(Exchange.HTTP_METHOD).constant("POST")
				.to("undertow:http://{{backend}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler").filter()
				.simple("${header.CamelHttpResponseCode} != 200").transform().xpath("//detail/text()", String.class)
				.end().filter().simple("${header.CamelHttpResponseCode} == 200").unmarshal(lbsoap).end();

		// Sberbank card payment service
		from("direct:sberbank").id("CallSberbank").setHeader(Exchange.CONTENT_TYPE)
				.constant("application/x-www-form-urlencoded").setHeader(Exchange.HTTP_METHOD).constant("POST")
				.to("undertow:https://{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext")
				.unmarshal().json(JsonLibrary.Fastjson, Map.class);
		
	}

}
