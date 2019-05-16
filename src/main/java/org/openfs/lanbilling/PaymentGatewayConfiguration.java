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
import org.springframework.context.annotation.Configuration;
import lb.api3.Api3PortType;

@Configuration
public class PaymentGatewayConfiguration extends RouteBuilder {

	@Override
	public void configure() throws Exception {

		SoapJaxbDataFormat lbsoap = new SoapJaxbDataFormat("lb.api3",
				new ServiceInterfaceStrategy(Api3PortType.class, true));

		restConfiguration().component("undertow").host("localhost").port("{{port}}").contextPath("/pay")
				.dataFormatProperty("com.fasterxml.jackson.databind.SerializationFeature.disableFeatures",
						"WRITE_NULL_MAP_VALUES");

//		rest("/sber/online").bindingMode(RestBindingMode.xml).consumes("application/xml").produces("application/xml")
//				.get().outType(SberOnlineResponse.class).route().routeId("ProcessSberOnline").process("sberOnline")
//				.endRest();

		rest("/sber/callback").bindingMode(RestBindingMode.off).get().route().routeId("ProcessSberCallback")
				.setExchangePattern(ExchangePattern.InOnly).process("sberCallback").endRest();

		rest("/checkout").enableCORS(true).bindingMode(RestBindingMode.off).produces("application/json").get().route().routeId("ProcessFormValidate")
				.setExchangePattern(ExchangePattern.InOnly).bean("formCheckout", "validate").endRest().post().route()
				.routeId("ProcessFormCheckout").setExchangePattern(ExchangePattern.InOnly)
				.bean("formCheckout", "checkout").endRest();

		// LanBilling SOAP service
		from("direct:lbsoap").id("LBcoreSoapBackend").onException(SOAPFaultException.class).handled(true)
				.log("LBCORE EXCEPTION:${body}").end().marshal(lbsoap).setHeader(Exchange.HTTP_METHOD).constant("POST")
				.to("undertow:http://{{lbcore}}?throwExceptionOnFailure=false&cookieHandler=#cookieHandler").filter()
				.simple("${header.CamelHttpResponseCode} != 200").transform().xpath("//detail/text()", String.class)
				.end().filter().simple("${header.CamelHttpResponseCode} == 200").unmarshal(lbsoap).end();

		// Sberbank card payment service
		from("direct:sberbank").id("SberbankBackend").setHeader(Exchange.CONTENT_TYPE)
				.constant("application/x-www-form-urlencoded").setHeader(Exchange.HTTP_METHOD).constant("POST")
				.to("undertow:{{sber.Url}}?throwExceptionOnFailure=false&sslContextParameters=#sslContext").unmarshal()
				.json(JsonLibrary.Fastjson, Map.class);

	}

}
