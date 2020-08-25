package ru.openfs.lbpay.sberonline;

import org.apache.camel.Handler;

public class SberOnlineResponseFactory {
 
    @Handler
    public static SberOnlineResponse success() {
        return new SberOnlineResponse(SberOnlineResponse.CodeResponse.OK);
    }

    @Handler
    public static SberOnlineResponse tempError() {
        return new SberOnlineResponse(SberOnlineResponse.CodeResponse.TMP_ERR);
    }
    
    @Handler
    public static SberOnlineResponse unknownRequest() {
        return new SberOnlineResponse(SberOnlineResponse.CodeResponse.WRONG_ACTION);
    }
    
    @Handler
    public static SberOnlineResponse accountNotFound() {
        return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_NOT_FOUND);
    }

    @Handler
    public static SberOnlineResponse accountBadFormat() {
        return new SberOnlineResponse(SberOnlineResponse.CodeResponse.ACCOUNT_WRONG_FORMAT);
    }

}