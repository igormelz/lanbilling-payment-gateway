package ru.openfs.lbpay.lbsoap;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import lb.api3.CancelPrePayment;
import lb.api3.ConfirmPrePayment;
import lb.api3.ExternCancelPayment;
import lb.api3.ExternCheckPayment;
import lb.api3.ExternCheckPaymentResponse;
import lb.api3.ExternPayment;
import lb.api3.ExternPaymentResponse;
import lb.api3.GetAccount;
import lb.api3.GetAccountResponse;
import lb.api3.GetAgreements;
import lb.api3.GetAgreementsResponse;
import lb.api3.GetExternAccount;
import lb.api3.GetExternAccountResponse;
import lb.api3.GetPrePayments;
import lb.api3.GetPrePaymentsResponse;
import lb.api3.GetRecommendedPayment;
import lb.api3.GetRecommendedPaymentResponse;
import lb.api3.InsPrePayment;
import lb.api3.InsPrePaymentResponse;
import lb.api3.Login;
import lb.api3.Logout;
import lb.api3.SoapAgreement;
import lb.api3.SoapFilter;
import lb.api3.SoapPayment;
import lb.api3.SoapPaymentFull;
import lb.api3.SoapPrePayment;
import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.lbsoap.model.LbCodeExternType;
import ru.openfs.lbpay.lbsoap.model.LbPaymentInfo;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponse;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponseStatus;
import ru.openfs.lbpay.sberonline.SberOnlineResponse;

@Service("lbsoap")
@Configuration
public class LbSoapService {
	private static final Logger LOG = LoggerFactory.getLogger(LbSoapService.class);

	private static final long STATUS_READY = 0;
	private static final long STATUS_PROCESSED = 1;
	private static final long STATUS_CANCELED = 2;

	DateTimeFormatter payDateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy_HH:mm:ss");
	DateTimeFormatter paymentDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@EndpointInject(uri = "direct:lbsoap-adapter")
	ProducerTemplate adapter;

	@EndpointInject("direct:lbsoap-login")
	ProducerTemplate lbcore;

	@Value("${lbcore.username}")
	private String name;

	@Value("${lbcore.password}")
	private String pwd;

	public LbSoapService() {
	}

	private Login getLogin() {
		Login login = new Login();
		login.setLogin(name);
		login.setPass(pwd);
		return login;
	}

	/**
	 * connect to lbcore
	 * 
	 * @return session token or null if error
	 */
	private String connect() {
		LOG.debug("LbSoap:connect");
		String token = lbcore.requestBody(getLogin(), String.class);
		if (token != null && !token.isEmpty()) {
			LOG.debug("Got session:{}", token);
			return token.replaceFirst("(sessnum=\\w+);.*", "$1");
		}
		return null;
	}

	/**
	 * disconnect from lbcore
	 */
	private void disconnect(String session) {
		LOG.debug("LbSoap:disconnect:{}", session);
		try {
			adapter.sendBodyAndHeader(new Logout(), "Cookie", session);
		} catch (CamelExecutionException e) {
			LOG.error("Disconnect exception:{}", e.getMessage());
		}
	}

	/**
	 * call billing to get agreement by number
	 */
	private Optional<SoapAgreement> findAgreement(String session, String number) {
		SoapFilter filter = new SoapFilter();
		filter.setAgrmnum(number);

		GetAgreements request = new GetAgreements();
		request.setFlt(filter);

		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			GetAgreementsResponse agreement = (GetAgreementsResponse) response.getBody();
			if (!agreement.getRet().isEmpty()) {
				return Optional.of(agreement.getRet().get(0));
			}
			LOG.warn("Agreement:{} is not found", number);
			return Optional.empty();
		}
		LOG.error("Error find agreement:{} - {}", number, response.getBody());
		return Optional.empty();
	}

	/**
	 * call billing to get agreement by agrm_id
	 */
	private Optional<SoapAgreement> findAgreement(String session, long agrm_id) {
		SoapFilter flt = new SoapFilter();
		flt.setAgrmid(agrm_id);
		GetAgreements request = new GetAgreements();
		request.setFlt(flt);
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			GetAgreementsResponse agreement = (GetAgreementsResponse) response.getBody();
			if (!agreement.getRet().isEmpty()) {
				return Optional.of(agreement.getRet().get(0));
			}
			LOG.warn("Agreement:{} is not found", agrm_id);
			return Optional.empty();
		}
		LOG.error("Error find agreement:{} - {}", agrm_id, response.getBody());
		return Optional.empty();
	}

	/**
	 * test agreement active or close
	 */
	@Handler
	public boolean isActiveAgreement(@Header(PaymentGatewayConstants.FORM_AGREEMENT) String number) {
		boolean active = false;
		String session = connect();
		if (session != null) {
			Optional<SoapAgreement> agreement = findAgreement(session, number);
			if (agreement.isPresent()) {
				active = agreement.get().getClosedon().isEmpty();
				if (!active) {
					LOG.warn("Agreement:{} is inactive", number);
				}
			}
			disconnect(session);
		}
		return active;
	}

	/**
	 * create payment order number
	 * 
	 * @return orderNumber
	 */
	@Handler
	public long createPrePaymentOrder(@Header(PaymentGatewayConstants.FORM_AGREEMENT) String number,
			@Header(PaymentGatewayConstants.FORM_AMOUNT) Double amount) {
		long orderNumber = 0l;
		String session = connect();
		if (session != null) {
			Optional<SoapAgreement> agreement = findAgreement(session, number);
			if (agreement.isPresent() && agreement.get().getClosedon().isEmpty()) {
				// create new payment order
				SoapPrePayment data = new SoapPrePayment();
				data.setAgrmid(agreement.get().getAgrmid());
				data.setAmount(amount);
				data.setCurname("RUR");
				data.setComment("form checkout");
				data.setPaydate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
				InsPrePayment request = new InsPrePayment();
				request.setVal(data);
				LbServiceResponse response = callService(session, request);
				if (response.isSuccess()) {
					orderNumber = ((InsPrePaymentResponse) response.getBody()).getRet();
				} else {
					LOG.error("Error insert prepayment - {}", response.getBody());
				}
			}
			disconnect(session);
		}
		return orderNumber;
	}

	/**
	 * get status name
	 */
	private String statusName(Long status) {
		return (status == STATUS_PROCESSED) ? "processed" : (status == STATUS_CANCELED) ? "canceled" : "ready";
	}

	/**
	 * call billing to find prepayment order
	 */
	private Optional<SoapPrePayment> findPrePayment(String session, long orderNumber) {
		SoapFilter flt = new SoapFilter();
		flt.setRecordid(orderNumber);
		GetPrePayments request = new GetPrePayments();
		request.setFlt(flt);
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			GetPrePaymentsResponse prepayment = (GetPrePaymentsResponse) response.getBody();
			if (!prepayment.getRet().isEmpty()) {
				LOG.info("Found prepayment orderNumber:{}, amount:{}, date:{}, status:{}", orderNumber,
						prepayment.getRet().get(0).getAmount(), prepayment.getRet().get(0).getPaydate(),
						statusName(prepayment.getRet().get(0).getStatus()));
				return Optional.of(prepayment.getRet().get(0));
			}
			LOG.warn("Not found prepayment orderNumber:{}", orderNumber);
			return Optional.empty();
		}
		LOG.error("Error find prepayment orderNumber:{} -- {}", orderNumber, response.getBody());
		return Optional.empty();
	}

	/**
	 * call billing to cancel prepayment by record_id
	 */
	private boolean cancelPrePayment(String session, Long record_id) {
		CancelPrePayment request = new CancelPrePayment();
		request.setRecordid(record_id);
		request.setCanceldate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			LOG.info("Prepayment orderNumber:{} canceled successfuly", record_id);
		} else {
			LOG.error("Error cancel prepayment orderNummber:{} - {}", record_id, response.getBody());
		}
		return response.isSuccess();
	}

	/**
	 * process cancel prepayment order
	 */
	@Handler
	public int processCancelPrePayment(@Header(PaymentGatewayConstants.ORDER_NUMBER) Long orderNumber) {
		int answer = PaymentGatewayConstants.INTERNAL_SERVER_ERROR;
		String session = connect();
		if (session != null) {
			// get prepayment order
			Optional<SoapPrePayment> order = findPrePayment(session, orderNumber);
			if (order.isPresent()) {
				// cancel order if not processed
				if (order.get().getStatus() == STATUS_READY) {
					// log agreement info
					findAgreement(session, order.get().getAgrmid()).ifPresent(agreement -> {
						LOG.info("Canceling prepayment orderNumber:{} for agreement:{}", orderNumber,
								agreement.getNumber());
					});
					if (cancelPrePayment(session, orderNumber)) {
						answer = PaymentGatewayConstants.OK;
					}
				} else {
					LOG.warn("Prepayment orderNumber:{} already processed", orderNumber);
					answer = PaymentGatewayConstants.OK;
				}
			} else {
				LOG.error("Prepayment orderNumber:{} not found", orderNumber);
				answer = PaymentGatewayConstants.NOT_FOUND;
			}
			disconnect(session);
		}
		return answer;
	}

	/**
	 * call billing find account
	 */
	private LbPaymentInfo getPaymentInfo(String session, Long uid, Double amount) {
		// find account
		GetAccount request = new GetAccount();
		request.setId(uid);
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			GetAccountResponse account = (GetAccountResponse) response.getBody();
			if (!account.getRet().isEmpty()) {
				return new LbPaymentInfo(account.getRet().get(0).getAccount().getAbonentname(),
						account.getRet().get(0).getAccount().getEmail(),
						account.getRet().get(0).getAccount().getMobile(), amount);
			}
			LOG.error("Not found account id:{}", uid);
			return null;
		}
		LOG.error("Not found account id:{} error:{}", uid, response.getBody());
		return null;
	}

	/**
	 * call billing to cancel payment
	 */
	private LbServiceResponse cancelPayment(String session, String receipt) {
		ExternCancelPayment request = new ExternCancelPayment();
		request.setReceipt(receipt);
		request.setNotexists(1L);
		return callService(session, request);
	}

	/**
	 * process refund payment
	 * 
	 * @return billingPaymentInfo or null if error
	 */
	@Handler
	public LbPaymentInfo processRefundOrder(@Header(PaymentGatewayConstants.ORDER_NUMBER) Long orderNumber,
			@Header(PaymentGatewayConstants.SBER_ORDER_NUMBER) String receipt) {
		LbPaymentInfo payment = null;
		String session = connect();
		if (session != null) {
			// find prepayment
			Optional<SoapPrePayment> prepayment = findPrePayment(session, orderNumber);
			if (prepayment.isPresent()) {
				// check status
				if (prepayment.get().getStatus() == STATUS_PROCESSED) {
					// get agreement info
					Optional<SoapAgreement> agreement = findAgreement(session, prepayment.get().getAgrmid());
					if (agreement.isPresent()) {
						LOG.info("Try to cancel payment orderNumber:{}, agreement:{}, amount:{}, receipt:{}",
								orderNumber, agreement.get().getNumber(), prepayment.get().getAmount(), receipt);
						// call billing to cancel payment
						LbServiceResponse response = cancelPayment(session, receipt);
						if (response.isSuccess()) {
							LOG.info("Success cancel payment orderNumber:{}, agreement:{}, amount:{}, receipt:{}",
									orderNumber, agreement.get().getNumber(), prepayment.get().getAmount(), receipt);
							payment = getPaymentInfo(session, agreement.get().getUid(), prepayment.get().getAmount());
						} else {
							// !!! critical audit point
							LOG.error("LB refused to cancel payment orderNumber:{}, receipt:{} - {}", orderNumber,
									receipt, response.getBody());
						}
					}
				} else {
					LOG.warn("Prepayment orderNumber:{} is not processed and will cancelled", orderNumber);
					cancelPrePayment(session, orderNumber);
				}
			}
			disconnect(session);
		}
		return payment;
	}

	/**
	 * commit pre payment
	 * 
	 * @param session
	 * @param prepayid
	 * @param amount
	 * @param receipt
	 * @return
	 */
	private LbServiceResponse confirmPrePayment(String session, Long prepayid, Double amount, String receipt) {
		ConfirmPrePayment request = new ConfirmPrePayment();
		request.setRecordid(prepayid);
		request.setAmount(amount);
		request.setReceipt(receipt);
		request.setPaydate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
		return callService(session, request);
	}

	/**
	 * find payment
	 * 
	 * @param session
	 * @param paymentId
	 * @return paymentinfo
	 */
	private Optional<SoapPaymentFull> findPayment(String session, String paymentId) {
		ExternCheckPayment request = new ExternCheckPayment();
		request.setReceipt(paymentId);
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			ExternCheckPaymentResponse payment = (ExternCheckPaymentResponse) response.getBody();
			return Optional.of(payment.getRet().get(0));
		}
		return Optional.empty();
	}

	/**
	 * process online payment
	 * 
	 * @param account
	 * @param amount
	 * @param payId
	 * @param payDate
	 * @return paymentResult
	 */
	@Handler
	public SberOnlineResponse processDirectPayment(@Header(PaymentGatewayConstants.ACCOUNT) String account,
			@Header(PaymentGatewayConstants.AMOUNT) Double amount, @Header(PaymentGatewayConstants.PAY_ID) String payId,
			@Header(PaymentGatewayConstants.PAY_DATE) String payDate) {

		// validate account format
		if (!account.matches("\\d+$")) {
			LOG.error("Error payment:{} - agreement:{} has bad format", payId, account);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_WRONG_FORMAT);
		}

		// parse payDate
		String paymentDateTime;
		try {
			paymentDateTime = LocalDateTime.parse(payDate, payDateFormat).format(paymentDateFormat);
		} catch (DateTimeException ex) {
			LOG.error("Error payment:{} - paydate:{} not parsed:{}", payId, payDate, ex.getMessage());
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_FORMAT_DATE);
		}

		// validate amount
		if (amount <= 0) {
			LOG.error("Error payment:{} - amount:{} too small", payId, amount);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.PAY_AMOUNT_TOO_SMALL);
		}

		// call backend
		SberOnlineResponse processResponse = null;
		String session = connect();
		if (session != null) {
			// fill payment data
			SoapPayment paymentData = new SoapPayment();
			paymentData.setPaydate(paymentDateTime);
			paymentData.setAmount(amount);
			paymentData.setComment("SberOnline");
			paymentData.setModperson(0L);
			paymentData.setCurrid(0L);
			paymentData.setReceipt(payId);
			paymentData.setClassid(0L);
			// fill payment request
			ExternPayment request = new ExternPayment();
			request.setVal(paymentData);
			request.setId(LbCodeExternType.AGRM_NUM.getCode());
			request.setStr(account);
			request.setNotexists(1L);
			request.setOperid(0L);
			LbServiceResponse response = callService(session, request);
			if (response.isSuccess()) {
				LOG.info("Success payment:{} agreement:{} amount:{}", payId, account, amount);
				processResponse = new SberOnlineResponse(SberOnlineResponse.CodeResponse.OK);
				// get transaction id
				ExternPaymentResponse ret = (ExternPaymentResponse) response.getBody();
				processResponse.setExtId(ret.getRet());
				// get transaction date
				Optional<SoapPaymentFull> payment = findPayment(session, payId);
				if (payment.isPresent()) {
					processResponse.setRegDate(LocalDateTime
							.parse(payment.get().getPay().getLocaldate(), paymentDateFormat).format(payDateFormat));
				}
				processResponse.setAmount(amount);
			} else if (response.isFault()) {
				String fault = (String) response.getBody();
				// check if was duplicate payment
				if (fault.contains("already exists")) {
					Optional<SoapPaymentFull> payment = findPayment(session, payId);
					if (payment.isPresent()) {
						LOG.error("Error payment:{} - has already done", payId);
						processResponse = new SberOnlineResponse(SberOnlineResponse.CodeResponse.PAY_TRX_DUPLICATE);
						processResponse
						.setExtId(Long.parseLong(fault.replaceFirst(".*\\(record_id = (\\d+)\\)$", "$1")));
						processResponse.setAmount(payment.get().getAmountcurr());
						processResponse.setRegDate(LocalDateTime
						.parse(payment.get().getPay().getLocaldate(), paymentDateFormat).format(payDateFormat));
					}
				} else if (fault.contains("not found")) {
					LOG.error("Error payment:{} - unknow agreement:{}", payId, account);
					processResponse = new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND);
				} else {
					LOG.error("Error payment:{} - {}", payId, fault);
				}
			}
			disconnect(session);
		}
		return processResponse == null ? new SberOnlineResponse(SberOnlineResponse.CodeResponse.TMP_ERR)
				: processResponse;
	}

	private Optional<Double> getRecPayment(String session, long argmid) {
		GetRecommendedPayment request = new GetRecommendedPayment();
		request.setId(argmid);
		LbServiceResponse response = callService(session, request);
		if (response.isSuccess()) {
			GetRecommendedPaymentResponse answer = (GetRecommendedPaymentResponse) response.getBody();
			return Optional.of(answer.getRet());
		}
		return Optional.empty();
	}

	/**
	 * process check exists agreement
	 * 
	 * @param account
	 * @return
	 */
	@Handler
	public SberOnlineResponse processCheckPayment(@Header(PaymentGatewayConstants.ACCOUNT) String account) {

		// validate account format
		if (!account.matches("\\d+$")) {
			LOG.error("Agreement:{} has bad format", account);
			return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_WRONG_FORMAT);
		}

		SberOnlineResponse processResponse = null;
		String session = connect();
		if (session != null) {

			GetExternAccount request = new GetExternAccount();
			request.setId(LbCodeExternType.AGRM_NUM.getCode());
			request.setStr(account);

			LbServiceResponse response = callService(session, request);
			if (response.isSuccess()) {
				GetExternAccountResponse accountInfo = (GetExternAccountResponse) response.getBody();
				if (!accountInfo.getRet().isEmpty()
						&& accountInfo.getRet().get(0).getAgreements().get(0).getClosedon().isEmpty()) {
					processResponse = new SberOnlineResponse(SberOnlineResponse.CodeResponse.OK);
					processResponse.setAddress(accountInfo.getRet().get(0).getAddresses().get(0).getAddress());
					processResponse.setBalance(accountInfo.getRet().get(0).getAgreements().get(0).getBalance());
					long argmid = accountInfo.getRet().get(0).getAgreements().get(0).getAgrmid();
					Optional<Double> recPayment = getRecPayment(session, argmid);
					if (recPayment.isPresent()) {
						processResponse.setRecSum(recPayment.get());
					}
				} else if (!accountInfo.getRet().isEmpty()
						&& !accountInfo.getRet().get(0).getAgreements().get(0).getClosedon().isEmpty()) {
					processResponse = new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_INACTIVE);
				}
			} else {
				LOG.error("Error check agreement:{} - {}", account, response.getBody());
			}
			disconnect(session);
		}
		return processResponse == null ? new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND)
				: processResponse;
	}

	/**
	 * process payment order
	 * 
	 * @param orderNumber
	 * @param receipt
	 * @return paymentInfo
	 */
	@Handler
	public LbPaymentInfo processPaymentOrder(@Header(PaymentGatewayConstants.ORDER_NUMBER) Long orderNumber,
			@Header(PaymentGatewayConstants.SBER_ORDER_NUMBER) String receipt) {
		LbPaymentInfo payment = null;
		String session = connect();
		if (session != null) {
			// check prepayment status by orderNumber
			Optional<SoapPrePayment> prepayment = findPrePayment(session, orderNumber);
			if (prepayment.isPresent() && prepayment.get().getStatus() == STATUS_READY) {
				// get agreement info
				Optional<SoapAgreement> agreement = findAgreement(session, prepayment.get().getAgrmid());
				if (agreement.isPresent()) {
					LOG.info("Do payment orderNumber:{} for agreement:{}", orderNumber, agreement.get().getNumber());
					// do payment
					LbServiceResponse response = confirmPrePayment(session, orderNumber, prepayment.get().getAmount(),
							receipt);
					if (response.isSuccess()) {
						LOG.info("Success payment orderNumber:{} for agreement:{} on amount:{}", orderNumber,
								agreement.get().getNumber(), prepayment.get().getAmount());
						// get payee info
						payment = getPaymentInfo(session, agreement.get().getUid(), prepayment.get().getAmount());
					} else {
						LOG.error("Error payment orderNummber:{} - {}", orderNumber, response.getBody());
					}
				}
			}
			disconnect(session);
		}
		return payment;
	}

	/**
	 * Call LB Request
	 */
	protected LbServiceResponse callService(String session, Object request) {
		try {
			Object response = adapter.requestBodyAndHeader(request, "Cookie", session);
			if (response == null) {
				return new LbServiceResponse(LbServiceResponseStatus.NO_RESPONSE, null);
			}
			if (response instanceof String) {
				return new LbServiceResponse(LbServiceResponseStatus.FAULT, response);
			}
			return new LbServiceResponse(LbServiceResponseStatus.SUCCESS, response);
		} catch (CamelExecutionException e) {
			LOG.error("ServiceCall:{} got exception:{}", request.getClass().getSimpleName(), e.getMessage());
			return new LbServiceResponse(LbServiceResponseStatus.ERROR, null);
		}
	}

}
