#!/usr/bin/env bats

function setup() {
  checkoutUrl="localhost/pay/checkout"
  sberUrl="localhost/pay/sber/callback/"
  dreamkasUrl="localhost/pay/dreamkas/"
  reprocUrl="localhost/pay/reprocessing/"
  AGRM_OK="190010"
  AGRM_INACTIVE="190004"
  AGRM_NOT_EXIST="190099"
  AGRM_WRONG_FORMAT="1x2x3x"
  AGRM_FAIL="123121"
  AMOUNT="10.12"
}

@test "validate agreement" {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $checkoutUrl -d "uid=${AGRM_OK}"`
  [ "$ret" -eq 200 ]
}

@test "validate inactive agreement " {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $checkoutUrl -d "uid=${AGRM_INACTIVE}"`
  [ "$ret" -ne 200 ]
}

@test "validate not existed agreement" {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $checkoutUrl -d "uid=${AGRM_NOT_EXIST}"`
  [ "$ret" -ne 200 ]
}

@test "concurrent validate agreement" {
  seq 9 | xargs -n1 -P3 sh -c "[[ $(curl -w "%{http_code}" -o "/dev/null" -s -G ${checkoutUrl} -d "uid=${AGRM_OK}") = 200 ]]"
}


@test "checkout fail on bad request" {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -XPOST $checkoutUrl -d "uie=werwer"`
  [ "$ret" -ne 200 ]
}

@test "checkout fail on not existed agreement" {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s $checkoutUrl -F "uid=${AGRM_NOT_EXIST}"`
  [ "$ret" -ne 200 ]
}

@test "checkout fail on wrong amount" {
  ret=`curl -w "%{http_code}" -o "/dev/null" -s $checkoutUrl -F "uid=${AGRM_OK}" -F "amount=1"`
  [ "$ret" -ne 200 ]
}

@test "checkout redirect to payment page" {
  run curl -w "%{http_code}\n%{redirect_url}" -o "/dev/null" -s $checkoutUrl -d "uid=${AGRM_OK}&amount=${AMOUNT}"
  [[ ${lines[0]} -eq 301 ]]
  [[ ${lines[1]} = https://3dsec.sberbank.ru* ]]
}

@test "sber format" {
  uuid=`uuidgen`
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "mdOrder=${uuid}&operation=deposited&status=0"`
  [[ $ret -ne 200 ]]
}

@test "sber operation" {
  uuid=`uuidgen`
  ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=11111&mdOrder=${uuid}&operation=depo&status=1"`
  [[ $ret -ne 200 ]]
}


@test "sber cancel deposited" {
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=0 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then 
    skip
  else
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=deposited&status=0"`
    [[ $ret -eq 200 ]]
  fi
}

@test "sber cancel" {
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=0 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then
    skip
  else
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=declinedByTimeout&status=0"`
    [[ $ret -eq 200 ]]
  fi
}

@test "sber refunded on not processed" {
  # create prepayment order
  run curl -o "/dev/null" -s "localhost/pay/autopayment" -d "uid=${AGRM_OK}&amount=${AMOUNT}"
  # get orderNumber
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=0 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then
    skip
  else
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=refunded&status=1"`
    [[ $ret -ne 200 ]]
  fi
}

@test "sber payment" {
  # create prepayment order
  run curl -o "/dev/null" -s "localhost/pay/autopayment" -d "uid=${AGRM_OK}&amount=${AMOUNT}" 
  # get orderNumber
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=0 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then
    skip
  else
    # callback
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=deposited&status=1"`
    [[ $ret -eq 200 ]]
    sleep 1

    # test receipt status
    receipt=$(mysql lbpay -ulbpay -plbpay -e "select operationStatus from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    [[ $receipt = PENDING ]]

    # test receipt amount 
    receipt_amount=$(mysql lbpay -ulbpay -plbpay -e "select round(amount,2) from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    [[ $receipt_amount = $AMOUNT ]]

    # commit receipt
    DATE=`date +"%Y-%m-%dT%H-%M-%S"`
    oper=$(mysql lbpay -ulbpay -plbpay -e "select operationId from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    rcpt=`curl -w "%{http_code}" -o "/dev/null" -s -H"Content-type:application/json" $dreamkasUrl -d "{\"action\":\"UPDATE\",\"type\":\"OPERATION\",\"data\":{\"createdAt\":\"${DATE}\",\"externalId\":\"${uuid}\",\"data\":{\"receiptId\":\"5dc9c73df7ea3538182de9e8\"},\"completedAt\":\"${DATE}\",\"type\":\"EXTERNAL_PURCHASE\",\"status\":\"SUCCESS\",\"id\":\"${oper}\"}}"`
    [[ $rcpt -eq 204 ]]

    # test receipt status
    sleep 1
    status=$(mysql lbpay -ulbpay -plbpay -e "select operationStatus from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    [[ $status = SUCCESS ]]
  fi
}

@test "sber callback refunded processed" {
  # get orderNumber payment
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=1 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then
    skip
  else
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=refunded&status=1"`
    [[ $ret -ne 200 ]]
  fi
}

@test "dreamkas webhook format" {
    DATE=`date +"%Y-%m-%dT%H-%M-%S"`
    rcpt=`curl -w "%{http_code}" -o "/dev/null" -s -H"Content-type:application/json" $dreamkasUrl -d "{\"action\":\"UPDATE\",\"data\":{}}"`
    [[ $rcpt -eq 204 ]]
}

@test "dreamkas webhook receipt" {
    DATE=`date +"%Y-%m-%dT%H-%M-%S"`
    rcpt=`curl -w "%{http_code}" -o "/dev/null" -s -H"Content-type:application/json" $dreamkasUrl -d  "{\"action\":\"CREATE\",\"type\":\"RECEIPT\",\"data\":{\"date\":\"${DATE}\",\"shiftId\":117,\"margin\":0,\"amount\":15000,\"payments\":[{\"amount\":15000,\"type\":\"CASHLESS\"}],\"discount\":0,\"positions\":[{\"amount\":15000,\"quantity\":1000,\"discounts\":[],\"price\":15000,\"name\":\"Оплата услуг связи\",\"tax\":\"NDS_NO_TAX\",\"id\":\"4a454b65-fed8-4810-ae30-09f1f0a4e80b\",\"type\":\"SERVICE\"}],\"type\":\"SALE\",\"deviceId\":7,\"userId\":7,\"fiscalDocumentSign\":\"23\",\"number\":2,\"fnNumber\":\"92\",\"depth\":1,\"registryNumber\":\"00\",\"cashier\":{\"name\":\"Админ\",\"_id\":\"5f7ea359e2f38a7b4\"},\"fiscalDocumentNumber\":\"204\",\"operationId\":\"5f7ea359e2f38a7b4\",\"_id\":\"5dcbe218f7ea357c3538a7b3\",\"shopId\":111,\"localDate\":\"${DATE}\",\"customer\":{\"phone\":\"+79111212121\"}}}"`
    [[ $rcpt -eq 204 ]]
}

@test "dreamkas error receipt" {
  # create prepayment order
  run curl -o "/dev/null" -s "localhost/pay/autopayment" -d "uid=${AGRM_OK}&amount=${AMOUNT}"
  # get orderNumber
  order=$(mysql billing -ubilling -pbilling -e 'select record_id from pre_payments where status=0 order by record_id desc limit 1' -B -N 2>/dev/null)
  uuid=`uuidgen`
  if [ -z "$order" ]; then
    skip
  else
    # callback
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -G $sberUrl -d "orderNumber=${order}&mdOrder=${uuid}&operation=deposited&status=1"`
    [[ $ret -eq 200 ]]

    # test receipt status
    sleep 1
    read -ra RECEIPT <<< $(mysql lbpay -ulbpay -plbpay -e "select operationStatus,mdOrder,operationId from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    [[ ${RECEIPT[0]} = PENDING ]]

    # commit receipt
    DATE=`date +"%Y-%m-%dT%H-%M-%S"`
    rcpt=`curl -w "%{http_code}" -o "/dev/null" -s -H"Content-type:application/json" $dreamkasUrl -d "{\"action\":\"UPDATE\",\"type\":\"OPERATION\",\"data\":{\"createdAt\":\"${DATE}\",\"externalId\":\"${RECEIPT[1]}\",\"data\":{\"error\":{\"message\":\"Cash is offline\",\"code\":\"CashOffline\"}},\"completedAt\":\"${DATE}\",\"type\":\"EXTERNAL_PURCHASE\",\"status\":\"ERROR\",\"id\":\"${RECEIPT[2]}\"}}"`
    [[ $rcpt -eq 204 ]]
    # test receipt status
    sleep 1
    status=$(mysql lbpay -ulbpay -plbpay -e "select operationStatus from receipts where orderNumber=${order}" -B -N 2>/dev/null)
    [[ $status = ERROR ]]
  fi
}

@test "dreamkas reprocessing" {
  # get error order receipt 
  order=$(mysql lbpay -ulbpay -plbpay -e 'select orderNumber from receipts where operationStatus="ERROR" limit 1' -B -N 2>/dev/null)
  if [ -z "$order" ]; then
    skip
  else 
    ret=`curl -w "%{http_code}" -o "/dev/null" -s -XPOST "${reprocUrl}${order}"`
    [[ $ret -eq 204 ]]
    sleep 2
    status=$(mysql lbpay -ulbpay -plbpay -e "select operationStatus from receipts where orderNumber=${order} order by id desc limit 1" -B -N 2>/dev/null)
    [[ $status = PENDING ]]
  fi
}
