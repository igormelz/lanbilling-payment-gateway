package org.openfs.lanbilling;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.w3c.dom.Document;

import org.junit.Assert;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class SberPaymentTest  {

	SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy_HH:mm:ss");
	
	@EndpointInject(uri = "mock:result")
	MockEndpoint resultEndpoint;

	//@EndpointInject(uri = "http://localhost:8080")
	@Autowired
	ProducerTemplate template;
	
	@Test
	public void testCheck() throws Exception {
		//resultEndpoint.expectedMessageCount(1);		
		Document out = template.requestBody("http://localhost:8080/pay/sberOnline?ACTION=check&ACCOUNT=Ф0003", null, Document.class);
		String code = out.getElementsByTagName("CODE").item(0).getTextContent();
		Assert.assertEquals("CODE", "0", code);
	}
	
	@Test
	public void testPayment() throws Exception {
		//resultEndpoint.expectedMessageCount(1);
		StringBuilder q = new StringBuilder("ACTION=payment&ACCOUNT=Ф0003");
		q.append("&PAY_ID=").append(UUID.randomUUID().toString().replaceAll("\\D","")); 
		q.append("&PAY_DATE=").append(sdf.format(new Date()));
		q.append("&AMOUNT=100.0");
		Document out = template.requestBody("http://localhost:8080/pay/sberOnline?"+q.toString(), null, Document.class);
		String code = out.getElementsByTagName("CODE").item(0).getTextContent();
		Assert.assertEquals("CODE", "0", code);
	}
}
