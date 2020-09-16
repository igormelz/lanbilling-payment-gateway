package ru.openfs.lbpay.sberonline;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "response")
@XmlAccessorType(XmlAccessType.NONE)
public class SberOnlineResponse {

   public enum CodeResponse {
      ERR(-1, "Внутренняя ошибка"), // don't define in sber proto, need 300
      OK(0, "Успешное завершение операции"), // success
      TMP_ERR(1, "Временная ошибка. Повторите запрос позже"), // temp unavailable
      WRONG_ACTION(2, "Неизвестный тип запроса"), // no check no payment
      ACCOUNT_NOT_FOUND(3, "Абонент не найден"), // ACCOUNT NOT FOUND
      ACCOUNT_WRONG_FORMAT(4, "Неверный формат идентификатора Плательщика"), //
      ACCOUNT_INACTIVE(5, "Счет Плательщика не активен"), //
      WRONG_TRX_FORMAT(6, "Неверное значение идентификатора транзакции"), //
      PAYMENT_NOT_AVAILABLE(7, "Прием платежа запрещен по техническим причинам"), //
      PAY_TRX_DUPLICATE(8, "Дублирование транзакции"), //
      PAY_AMOUNT_ERROR(9, "Неверная сумма платежа"), //
      PAY_AMOUNT_TOO_SMALL(10, "Сумма слишком мала"), //
      PAY_AMOUNT_TOO_BIG(11, "Сумма слишком велика"), //
      WRONG_FORMAT_DATE(12, "Неверное значение даты"), //
      BACKEND_ERR(300, "Внутренняя ошибка Организации");

      private final int code;
      private final String msg;

      CodeResponse(int code, String msg) {
         this.code = code;
         this.msg = msg;
      }

      public String getMsg() {
         return this.msg;
      }

      public int getCode() {
         return this.code;
      }
   }

   @XmlElement(name = "CODE")
   private int code;

   @XmlElement(name = "MESSAGE")
   private String message;

   @XmlElement(name = "FIO")
   private String fio;

   @XmlElement(name = "ADDRESS")
   private String address;

   @XmlElement(name = "BALANCE")
   private Double balance;

   @XmlElement(name = "INFO")
   private String info;

   @XmlElement(name = "REG_DATE")
   private String regDate;

   @XmlElement(name = "AMOUNT")
   private Double amount;

   @XmlElement(name = "SUM")
   private Double sum;

   @XmlElement(name = "REC_SUM")
   private Double recSum;

   @XmlElement(name = "EXT_ID")
   private Long extId;

   public SberOnlineResponse() {
   }

   public void setMessage(String msg) {
      this.message = msg;
   }
   
   public void setFio(String fio) {
      this.fio = fio;
   }

   public void setAddress(String address) {
      this.address = address;
   }

   public void setBalance(Double balance) {
      this.balance = balance;
   }

   public void setInfo(String info) {
      this.info = info;
   }

   public void setRegDate(String regDate) {
      this.regDate = regDate;
   }

   public void setAmount(Double amount) {
      this.amount = amount;
   }

   public void setSum(Double sum) {
      this.sum = sum;
   }

   public void setRecSum(Double recSum) {
      this.recSum = recSum;
   }

   public void setExtId(Long extId) {
      this.extId = extId;
   }
   
   // base response
   public SberOnlineResponse(CodeResponse codeResponse) {
      this.code = codeResponse.getCode();
      this.message = codeResponse.getMsg();
   }

}