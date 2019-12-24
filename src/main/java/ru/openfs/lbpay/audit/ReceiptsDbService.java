package ru.openfs.lbpay.audit;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import ru.openfs.lbpay.PaymentGatewayConstants;
import ru.openfs.lbpay.dreamkas.model.Operation;

@Service("audit")
@Profile("prom")
public class ReceiptsDbService {
    private static Logger LOG = LoggerFactory.getLogger(ReceiptsDbService.class);

    @EndpointInject(uri = "jdbc:dataSource")
    protected ProducerTemplate producer;

    /**
     * audit start register receipt
     */
    @Handler
    public void registerReceipt(@Header(PaymentGatewayConstants.RECEIPT_TYPE) String receiptType,
            @Header("orderNumber") Long orderNumber, @Header("mdOrder") String mdOrder,
            @Header(PaymentGatewayConstants.ORDER_AMOUNT) double amount,
            @Header(PaymentGatewayConstants.CUSTOMER_EMAIL) String email,
            @Header(PaymentGatewayConstants.CUSTOMER_PHONE) String phone) {
        StringBuilder sql = new StringBuilder("insert into receipts set receiptType='").append(receiptType).append("',")
                .append("orderNumber=").append(orderNumber).append(",").append("mdOrder='").append(mdOrder).append("',")
                .append("amount=").append(amount).append(",").append("email='").append(email).append("',")
                .append("phone='").append(phone).append("'");
        try {
            producer.sendBody(sql.toString());
        } catch (CamelExecutionException e) {
            LOG.error("registerReceipt:{}", e.getMessage());
        }
    }

    /**
     * lookup audit db for error receipt
     */
    @Handler
    public void getErrorReceipt(@Header("orderNumber") Long orderNumber, Message message) {
        StringBuilder sql = new StringBuilder("select o.mdOrder, o.amount, o.phone, o.email, o.receiptType ")
                .append("from (select *,coalesce(operationStatus,'NONE') as status from receipts where orderNumber=").append(orderNumber)
                .append(" order by id desc limit 1) o ").append("where o.status !='SUCCESS'");
        try {
            Object answer = producer.requestBody(sql.toString());
            if (answer instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) answer;
                if (list.size() > 0) {
                    message.setHeader("mdOrder", list.get(0).get("mdOrder"));
                    message.setHeader(PaymentGatewayConstants.ORDER_AMOUNT, list.get(0).get("amount"));
                    message.setHeader(PaymentGatewayConstants.CUSTOMER_PHONE, list.get(0).get("phone"));
                    message.setHeader(PaymentGatewayConstants.CUSTOMER_EMAIL, list.get(0).get("email"));
                    message.setHeader(PaymentGatewayConstants.RECEIPT_TYPE, list.get(0).get("receiptType"));
                }
            }
        } catch (CamelExecutionException e) {
            LOG.error(e.getMessage());
        }
    }

    /**
     * audit success registered receipt
     */
    @Handler
    public void setReceiptOperation(Operation operation) {
        StringBuilder sql = new StringBuilder("update receipts set operationStatus='").append(operation.getStatus())
                .append("',").append("operationDate='").append(operation.getCreatedAt()).append("',")
                .append("operationId='").append(operation.getId()).append("'").append(" where mdOrder='")
                .append(operation.getExternalId()).append("' and operationId is null");
        try {
            producer.sendBody(sql.toString());
        } catch (CamelExecutionException e) {
            LOG.error("setReceiptOperation:{}", e.getMessage());
        }
    }

    /**
     * audit register receipt operations
     */
    @Handler
    public void updateReceiptOperation(Map<String, Object> body) {
        if (body != null) {
            ObjectMapper mapper = new ObjectMapper();
            Operation operation = mapper.convertValue(body, Operation.class);

            StringBuilder sql = new StringBuilder("update receipts set operationStatus='");
            sql.append(operation.getStatus()).append("',");
            if (operation.getStatus().toString().equalsIgnoreCase("ERROR")) {
                LOG.error("Receipt mdOrder:{}, operation:{} [{}]", operation.getExternalId(), operation.getId(),
                        operation.getData().getError().getCode());
                sql.append("operationMessage='").append(operation.getData().getError().getMessage()).append("',");
            } else {
                LOG.info("Receipt mdOrder:{}, operation:{} [{}]", operation.getExternalId(), operation.getId(),
                        operation.getStatus());
            }
            sql.append("operationDate='").append(
                    (operation.getCompletedAt() != null) ? operation.getCompletedAt() : operation.getCreatedAt())
                    .append("'");
            sql.append(" where operationId='").append(operation.getId()).append("'");
            try {
                producer.sendBody(sql.toString());
            } catch (CamelExecutionException e) {
                LOG.error("updateReceipt:{}", e.getMessage());
            }
        }
    }
}