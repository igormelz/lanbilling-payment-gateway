package org.openfs.lanbilling;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import lb.api3.CancelPrePayment;
import lb.api3.ConfirmPrePayment;
import lb.api3.ExternCancelPayment;
import lb.api3.GetAccounts;
import lb.api3.GetAccountsResponse;
import lb.api3.GetAgreements;
import lb.api3.GetAgreementsResponse;
import lb.api3.GetExternAccount;
import lb.api3.GetExternAccountResponse;
import lb.api3.GetPrePayments;
import lb.api3.GetPrePaymentsResponse;
import lb.api3.InsPrePayment;
import lb.api3.InsPrePaymentResponse;
import lb.api3.Login;
import lb.api3.LoginResponse;
import lb.api3.Logout;
import lb.api3.Payment;
import lb.api3.SoapAccountFull;
import lb.api3.SoapFilter;
import lb.api3.SoapPayment;
import lb.api3.SoapPrePayment;

@Service
@Configuration
public class LbSoapService {
	private static final Logger LOG = LoggerFactory.getLogger(LbSoapService.class);
	private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public static final String FIELD_NAME = "name";
	public static final String FIELD_PHONE = "phone";
	public static final String FIELD_EMAIL = "email";
	public static final String FIELD_UID = "uid";
	public static final String FIELD_AMOUNT = "amount";
	public static final String FIELD_AGREEMENT_ID = "agrmId";
	public static final String FIELD_RECEIPT = "receipt";
	public static final String FIELD_PAYMENT_ID = "paymentId";
	public static final String FIELD_ORDER_NUMBER = "orderNumber";
	public static final String FIELD_PREPAYMENT_PAY_DATE = "prepaymentDate";
	public static final String FIELD_PREPAYMENT_STATUS = "prepaymentStatus";
	public static final String FIELD_PREPAYMENT_CANCEL_DATE = "prepaymentCancelDate";
	
	@EndpointInject(uri = "direct:lbsoap")
	ProducerTemplate producer;

	@Value("${lbcore.username}")
	private String name;

	@Value("${lbcore.password}")
	private String pwd;

	private Login login;
	private long managerId;

	public LbSoapService() {
	}

	public long getManagerId() {
		return managerId;
	}

	public boolean connect() {
		ServiceResponse response = callService(getLogin());
		if (response.getStatus() != ServiceResponseStatus.SUCCESS) {
			LOG.error("Connect fail");
			return false;
		}

		LoginResponse loginResponse = (LoginResponse) response.getBody();
		if (loginResponse.getRet().isEmpty()) {
			LOG.error("Connect return empty LoginResponse");
			return false;
		}

		// save manager ID for using on next service calls
		this.managerId = loginResponse.getRet().get(0).getManager().getPersonid();
		return true;
	}

	public void disconnect() {
		try {
			producer.sendBody(new Logout());
		} catch (CamelExecutionException e) {
			LOG.error("Disconnect exception:{}", e.getMessage());
		}
	}

	public ServiceResponse insertPrePayment(Long agreementId, Double amount) {
		// fill payment data
		SoapPrePayment data = new SoapPrePayment();
		data.setAgrmid(agreementId);
		data.setAmount(amount);
		data.setCurname("RUR");
		data.setComment("checkout");
		data.setPaydate(sdf.format(new Date()));

		// insert pre-payment
		InsPrePayment request = new InsPrePayment();
		request.setVal(data);
		ServiceResponse response = callService(request);
		if (response.getStatus() == ServiceResponseStatus.SUCCESS) {
			// parse response
			InsPrePaymentResponse answer = (InsPrePaymentResponse) response.getBody();
			return new ServiceResponse(response.getStatus(), Collections.singletonMap(FIELD_ORDER_NUMBER, answer.getRet()));
		}
		return response;
	}

	public ServiceResponse getAccountByAgreementNumber(String account) {
		SoapFilter flt = new SoapFilter();
		flt.setAgrmnum(account);
		GetAccounts request = new GetAccounts();
		request.setFlt(flt);
		ServiceResponse response = callService(request);
		if (response.getStatus() == ServiceResponseStatus.SUCCESS) {
			// parse response
			GetAccountsResponse answer = (GetAccountsResponse) response.getBody();
			if (answer.getRet().isEmpty()) {
				return new ServiceResponse(ServiceResponseStatus.EMPTY_RESPONSE, null);
			}
			HashMap<String, Object> values = new HashMap<String, Object>();
			values.put(FIELD_NAME, answer.getRet().get(0).getAccount().getName());
			values.put(FIELD_PHONE, answer.getRet().get(0).getAccount().getMobile());
			values.put(FIELD_EMAIL, answer.getRet().get(0).getAccount().getEmail());
			values.put(FIELD_UID, answer.getRet().get(0).getAccount().getUid());
			return new ServiceResponse(response.getStatus(), values);
		}
		return response;
	}

	public ServiceResponse getAgreementId(long uid) {
		SoapFilter flt = new SoapFilter();
		flt.setUserid(uid);
		GetAgreements request = new GetAgreements();
		request.setFlt(flt);
		ServiceResponse response = callService(request);
		if (response.getStatus() == ServiceResponseStatus.SUCCESS) {
			// parse response
			GetAgreementsResponse answer = (GetAgreementsResponse) response.getBody();
			if (answer.getRet().isEmpty()) {
				return new ServiceResponse(ServiceResponseStatus.EMPTY_RESPONSE, null);
			}
			return new ServiceResponse(response.getStatus(),
					Collections.singletonMap(FIELD_AGREEMENT_ID, answer.getRet().get(0).getAgrmid()));

		}
		return response;
	}

	public ServiceResponse doPayment(String receipt, Double amount, long agreementId) {
		SoapPayment val = new SoapPayment();
		val.setModperson(this.managerId);
		val.setReceipt(receipt);
		val.setAmount(amount);
		val.setPaydate(sdf.format(new Date()));
		val.setAgrmid(agreementId);
		val.setComment("PaymentGateway:SberOnline");

		Payment request = new Payment();
		request.setVal(val);
		return callService(request);
	}

	public ServiceResponse cancelPayment(String receipt) {
		ExternCancelPayment request = new ExternCancelPayment();
		request.setReceipt(receipt);
		request.setNotexists(1L);
		return callService(request);
	}

	public ServiceResponse getAccount(CodeExternType id, String str) {
		GetExternAccount request = new GetExternAccount();
		request.setId(id.getCode());
		request.setStr(str);
		ServiceResponse response = callService(request);
		if (response.isSuccess()) {
			GetExternAccountResponse answer = (GetExternAccountResponse) response.getBody();
			if (answer.getRet().isEmpty()) {
				return new ServiceResponse(ServiceResponseStatus.EMPTY_RESPONSE, null);
			}
			SoapAccountFull account = answer.getRet().get(0);
			// put contract number to contract id
			Map<String, Object> values = account.getAgreements().stream()
					.collect(Collectors.toMap(a -> a.getNumber(), a -> a.getAgrmid()));
			// put account name
			values.put(FIELD_NAME, account.getAccount().getName());
			return new ServiceResponse(response.getStatus(), values);
		}
		return response;
	}

	public ServiceResponse cancelPrePayment(Long prepayid) {
		CancelPrePayment request = new CancelPrePayment();
		request.setRecordid(prepayid);
		request.setCanceldate(sdf.format(new Date()));
		return callService(request);
	}

	public ServiceResponse confirmPrePayment(Long prepayid, Double amount, String receipt) {
		ConfirmPrePayment request = new ConfirmPrePayment();
		request.setRecordid(prepayid);
		request.setAmount(amount);
		request.setReceipt(receipt);
		request.setPaydate(sdf.format(new Date()));
		return callService(request);
	}

	public ServiceResponse getPrePayment(Long prepayid) {
		SoapFilter flt = new SoapFilter();
		flt.setRecordid(prepayid);
		GetPrePayments request = new GetPrePayments();
		request.setFlt(flt);
		ServiceResponse response = callService(request);
		if (response.getStatus() == ServiceResponseStatus.SUCCESS) {
			// parse response
			GetPrePaymentsResponse answer = (GetPrePaymentsResponse) response.getBody();
			if (answer.getRet().isEmpty()) {
				return new ServiceResponse(ServiceResponseStatus.EMPTY_RESPONSE, null);
			}
			HashMap<String, Object> values = new HashMap<String, Object>();
			values.put(FIELD_AGREEMENT_ID, answer.getRet().get(0).getAgrmid());
			values.put(FIELD_AMOUNT, answer.getRet().get(0).getAmount());
			values.put(FIELD_PAYMENT_ID, answer.getRet().get(0).getPaymentid());
			values.put(FIELD_RECEIPT, answer.getRet().get(0).getReceipt());
			values.put(FIELD_PREPAYMENT_PAY_DATE, answer.getRet().get(0).getPaydate());
			values.put(FIELD_PREPAYMENT_CANCEL_DATE, answer.getRet().get(0).getCanceldate());
			values.put(FIELD_PREPAYMENT_STATUS,answer.getRet().get(0).getStatus());
			return new ServiceResponse(response.getStatus(), values);
		}
		return response;
	}

	protected ServiceResponse callService(Object request) {
		try {
			Object response = producer.requestBody(request);
			if (response == null) {
				return new ServiceResponse(ServiceResponseStatus.NO_RESPONSE, null);
			}
			if (response instanceof String) {
				return new ServiceResponse(ServiceResponseStatus.FAULT, response);
			}
			return new ServiceResponse(ServiceResponseStatus.SUCCESS, response);
		} catch (CamelExecutionException e) {
			LOG.error("ServiceCall:{} got exception:{}", request.getClass().getSimpleName(), e.getMessage());
			return new ServiceResponse(ServiceResponseStatus.ERROR, null);
		}
	}

	private Login getLogin() {
		if (login != null) {
			return login;
		}
		LOG.info("Create Login");
		this.login = new Login();
		this.login.setLogin(name);
		this.login.setPass(pwd);
		return login;
	}

	public enum CodeExternType {
		VG_LOGIN(0), // vgroups.login
		USER_LOGIN(1), // accounts.login
		TEL_STAFF(2), // tel_staff.phone_number ( ts join vgroups v on ts.vg_id = v.vg_id )
		STAFF(3), // staff.segment ( ts join vgroups v on ts.vg_id = v.vg_id )
		FIO(4), // accounts.name
		AGRM_NUM(5), // agreements.number
		// KOD_1C(6),
		AGRM_CODE(6), // agreements.code
		EMAIL(7), // accounts.email
		ORDER(8), VG_ID(9), // vgroups.vg_id
		UID(10), // accounts.uid
		AGRM_ID(11), // agreements.agrm_id
		SBRF_RYAZAN(12), // agreements.number, agreements_addons_vals.str_value
		AGRM_ADDON(13), // options, name = old_agreement
		ORDER_ID(14), // orders.order_id
		VG_LOGIN_SUFFIX(15), // part of vgroups.login, using options.name = extern_payment_regexp_suffix
		AGRM_NUM_TYPE(16), // Saratov's agreement number with type of account
		OPER_ID(17); // agreements.oper_id

		private final long code;

		CodeExternType(long code) {
			this.code = code;
		}

		public long getCode() {
			return this.code;
		}
	}

	public enum ServiceResponseStatus {
		SUCCESS, NO_RESPONSE, FAULT, ERROR, EMPTY_RESPONSE
	}

	public class ServiceResponse implements Serializable {
		private static final long serialVersionUID = 2472612378764146433L;
		private final ServiceResponseStatus status;
		private final Object body;

		ServiceResponse(ServiceResponseStatus status, Object body) {
			this.status = status;
			this.body = body;
		}

		public ServiceResponseStatus getStatus() {
			return this.status;
		}

		public boolean isSuccess() {
			return this.status == ServiceResponseStatus.SUCCESS;
		}

		public boolean isFault() {
			return this.status == ServiceResponseStatus.FAULT;
		}

		public Object getBody() {
			return this.body;
		}

		@SuppressWarnings("unchecked")
		public Map<String, Object> getValues() {
			if (this.body instanceof Map) {
				return (Map<String, Object>) this.body;
			}
			return null;
		}

		@SuppressWarnings("unchecked")
		public Object getValue(String name) {
			if (!(this.body instanceof Map)) {
				return null;
			}
			Map<String, Object> values = (Map<String, Object>) this.body;
			if (!values.containsKey(name)) {
				return null;
			}
			return values.get(name);
		}
		
		public Long getLong(String name) {
			return getValue(name) != null ? (long) getValue(name) : null;
		}
		
		public String getString(String name) {
			return getValue(name) != null ? getValue(name).toString() : null;
		}
		
	}

}
