package ru.openfs.lbpay.lbsoap;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.undertow.util.StatusCodes;
import lb.api3.CancelPrePayment;
import lb.api3.ConfirmPrePayment;
import lb.api3.ExternCancelPayment;
import lb.api3.GetAccount;
import lb.api3.GetAccountResponse;
import lb.api3.GetAgreements;
import lb.api3.GetAgreementsResponse;
import lb.api3.GetPrePayments;
import lb.api3.GetPrePaymentsResponse;
import lb.api3.InsPrePayment;
import lb.api3.InsPrePaymentResponse;
import lb.api3.Login;
import lb.api3.Logout;
import lb.api3.SoapAgreement;
import lb.api3.SoapFilter;
import lb.api3.SoapPrePayment;
import ru.openfs.lbpay.lbsoap.model.LbPaymentInfo;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponse;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponseStatus;

@Service
@Configuration
@Profile("prom")
public class LbSoapService {
	private static final Logger LOG = LoggerFactory.getLogger(LbSoapService.class);

	private static final long STATUS_READY = 0;
	private static final long STATUS_PROCESSED = 1;
	private static final long STATUS_CANCELED = 2;

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
	 * call billing to get agreement by nuber
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
	public boolean isActiveAgreement(String number) {
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
	public long createPrePaymentOrder(String number, Double amount) {
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
	public int processCancelPrePayment(Long orderNumber) {
		int answer = StatusCodes.INTERNAL_SERVER_ERROR;
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
						answer = StatusCodes.OK;
					}
				} else {
					LOG.warn("Prepayment orderNumber:{} already processed", orderNumber);
					answer = StatusCodes.OK;
				}
			} else {
				LOG.error("Prepayment orderNumber:{} not found", orderNumber);
				answer = StatusCodes.NOT_FOUND;
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
	public LbPaymentInfo processRefundOrder(Long orderNumber, String receipt) {
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
	 * call billing to commit payment
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
	 * process payment order
	 */
	public LbPaymentInfo processPaymentOrder(Long orderNumber, String receipt) {
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
