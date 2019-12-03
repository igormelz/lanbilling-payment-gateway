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
import lb.api3.LoginResponse;
import lb.api3.Logout;
import lb.api3.SoapAgreement;
import lb.api3.SoapFilter;
import lb.api3.SoapPrePayment;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponse;
import ru.openfs.lbpay.lbsoap.model.LbServiceResponseStatus;
import ru.openfs.lbpay.lbsoap.model.LbSoapPaymentInfo;

@Service
@Configuration
@Profile("prom")
public class LbSoapService {
	private static final Logger LOG = LoggerFactory.getLogger(LbSoapService.class);

	private static final long STATUS_READY = 0;
	private static final long STATUS_PROCESSED = 1;
	private static final long STATUS_CANCELED = 2;

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

	/**
	 * connect to lbcore
	 */
	private boolean connect() {
		LOG.debug("LbSoap:connect");
		LbServiceResponse response = callService(getLogin());
		if (!response.isSuccess()) {
			LOG.error("Fail connect to lbcore");
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

	/**
	 * disconnect from lbcore
	 */
	private void disconnect() {
		LOG.debug("LbSoap:disconnect");
		try {
			producer.sendBody(new Logout());
		} catch (CamelExecutionException e) {
			LOG.error("Disconnect exception:{}", e.getMessage());
		}
	}

	/**
	 * call billing to get agreement by nuber
	 */
	private Optional<SoapAgreement> findAgreement(String number) {
		SoapFilter filter = new SoapFilter();
		filter.setAgrmnum(number);

		GetAgreements request = new GetAgreements();
		request.setFlt(filter);

		LbServiceResponse response = callService(request);
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
	private Optional<SoapAgreement> findAgreement(long agrm_id) {
		SoapFilter flt = new SoapFilter();
		flt.setAgrmid(agrm_id);
		GetAgreements request = new GetAgreements();
		request.setFlt(flt);
		LbServiceResponse response = callService(request);
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
		if (connect()) {
			Optional<SoapAgreement> agreement = findAgreement(number);
			if (agreement.isPresent()) {
				active = agreement.get().getClosedon().isEmpty();
				if (!active) {
					LOG.warn("Agreement:{} is inactive", number);
				}
			}
			disconnect();
		}
		return active;
	}

	/**
	 * call billing to new payment order
	 */
	private LbServiceResponse insertPrePayment(Long agreementId, Double amount) {
		SoapPrePayment data = new SoapPrePayment();
		data.setAgrmid(agreementId);
		data.setAmount(amount);
		data.setCurname("RUR");
		data.setComment("form checkout");
		data.setPaydate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));

		InsPrePayment request = new InsPrePayment();
		request.setVal(data);

		return callService(request);
	}

	/**
	 * create payment order number
	 * 
	 * @return orderNumber
	 */
	public long createPrePaymentOrder(String number, Double amount) {
		long orderNumber = 0l;
		if (connect()) {
			Optional<SoapAgreement> agreement = findAgreement(number);
			if (agreement.isPresent() && agreement.get().getClosedon().isEmpty()) {
				LbServiceResponse response = insertPrePayment(agreement.get().getAgrmid(), amount);
				if (response.isSuccess()) {
					orderNumber = ((InsPrePaymentResponse) response.getBody()).getRet();
				} else {
					LOG.error("Error insert prepayment - {}", response.getBody());
				}
			}
			disconnect();
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
	private Optional<SoapPrePayment> findPrePayment(long orderNumber) {
		SoapFilter flt = new SoapFilter();
		flt.setRecordid(orderNumber);
		GetPrePayments request = new GetPrePayments();
		request.setFlt(flt);
		LbServiceResponse response = callService(request);
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
	private void cancelPrePayment(Long record_id) {
		CancelPrePayment request = new CancelPrePayment();
		request.setRecordid(record_id);
		request.setCanceldate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
		LbServiceResponse response = callService(request);
		if (response.isSuccess()) {
			LOG.info("Prepayment orderNumber:{} canceled successfuly", record_id);
		} else {
			LOG.error("Error cancel prepayment orderNummber:{} - {}", record_id, response.getBody());
		}
	}

	/**
	 * process cancel prepayment order
	 */
	public int processCancelPrePayment(Long orderNumber) {
		if (connect()) {
			// get prepayment info
			findPrePayment(orderNumber).ifPresent(prepayment -> {
				// prepayment is ready
				if (prepayment.getStatus() == STATUS_READY) {
					// get agreement info
					findAgreement(prepayment.getAgrmid()).ifPresent(agreement -> {
						LOG.info("Canceling prepayment orderNumber:{} for agreement:{}", orderNumber,
								agreement.getNumber());
						cancelPrePayment(orderNumber);
					});
				} else {
					LOG.warn("Prepayment orderNumber:{} already processed", orderNumber);
				}
			});
			disconnect();
			return StatusCodes.OK;
		}
		return StatusCodes.INTERNAL_SERVER_ERROR;
	}

	/**
	 * call billing find account
	 */
	private LbSoapPaymentInfo findAccount(Long uid) {
		GetAccount request = new GetAccount();
		request.setId(uid);
		LbServiceResponse response = callService(request);
		if (response.isSuccess()) {
			GetAccountResponse accountResponse = (GetAccountResponse) response.getBody();
			return new LbSoapPaymentInfo(accountResponse.getRet().get(0).getAccount().getAbonentname(),
					accountResponse.getRet().get(0).getAccount().getEmail(),
					accountResponse.getRet().get(0).getAccount().getMobile());
		}
		return null;
	}

	/**
	 * call billing to cancel payment
	 */
	private LbServiceResponse cancelPayment(String receipt) {
		ExternCancelPayment request = new ExternCancelPayment();
		request.setReceipt(receipt);
		request.setNotexists(1L);
		return callService(request);
	}

	/**
	 * process refund payment
	 * 
	 * @return billingPaymentInfo or null if error
	 */
	public LbSoapPaymentInfo processRefundOrder(Long orderNumber, String receipt) {
		LbSoapPaymentInfo payment = null;
		if (connect()) {
			// find prepayment
			Optional<SoapPrePayment> prepayment = findPrePayment(orderNumber);
			if (prepayment.isPresent()) {
				// check status
				if (prepayment.get().getStatus() == STATUS_PROCESSED) {
					// get agreement info
					Optional<SoapAgreement> agreement = findAgreement(prepayment.get().getAgrmid());
					if (agreement.isPresent()) {
						LOG.info("Try to cancel payment orderNumber:{}, agreement:{}, amount:{}, receipt:{}",
								orderNumber, agreement.get().getNumber(), prepayment.get().getAmount(), receipt);
						// call billing to cancel payment
						LbServiceResponse response = cancelPayment(receipt);
						if (response.isSuccess()) {
							payment = findAccount(agreement.get().getUid());
							payment.setAmount(prepayment.get().getAmount());
							LOG.info("Success cancel payment orderNumber:{}, agreement:{}, amount:{}, receipt:{}",
									orderNumber, agreement.get().getNumber(), prepayment.get().getAmount(), receipt);
						} else {
							// !!! critical audit point
							LOG.error("LB refused to cancel payment orderNumber:{}, receipt:{} - {}", orderNumber,
									receipt, response.getBody());
						}
					}
				} else {
					LOG.warn("Prepayment orderNumber:{} is not processed and will cancelled");
					cancelPrePayment(orderNumber);
				}
			}
			disconnect();
		}
		return payment;
	}

	/**
	 * call billing to commit payment
	 */
	private LbServiceResponse confirmPrePayment(Long prepayid, Double amount, String receipt) {
		ConfirmPrePayment request = new ConfirmPrePayment();
		request.setRecordid(prepayid);
		request.setAmount(amount);
		request.setReceipt(receipt);
		request.setPaydate(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
		return callService(request);
	}

	/**
	 * process payment order
	 */
	public LbSoapPaymentInfo processPaymentOrder(Long orderNumber, String receipt) {
		LbSoapPaymentInfo payment = null;
		if (connect()) {
			// find prepayment
			Optional<SoapPrePayment> prepayment = findPrePayment(orderNumber);
			if (prepayment.isPresent()) {
				// check status
				if (prepayment.get().getStatus() == STATUS_READY) {
					// get agreement info
					Optional<SoapAgreement> agreement = findAgreement(prepayment.get().getAgrmid());
					if (agreement.isPresent()) {
						LOG.info("Do payment orderNumber:{} for agreement:{}", orderNumber,
								agreement.get().getNumber());
						// do payment
						LbServiceResponse response = confirmPrePayment(orderNumber, prepayment.get().getAmount(),
								receipt);
						if (response.isSuccess()) {
							LOG.info("Success payment orderNumber:{} for agreement:{} on amount:{}", orderNumber,
									agreement.get().getNumber(), prepayment.get().getAmount());
							// get payee info
							payment = findAccount(agreement.get().getUid());
							if (payment != null) {
								payment.setAmount(prepayment.get().getAmount());
							} else {
								LOG.error("No payment info for orderNumber:{}", orderNumber);
							}
						} else {
							LOG.error("Error payment orderNummber:{} - {}", orderNumber, response.getBody());
						}
					}
				}
			}
			disconnect();
		}
		return payment;
	}

	/**
	 * Call LB Request
	 */
	protected LbServiceResponse callService(Object request) {
		try {
			Object response = producer.requestBody(request);
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

}
