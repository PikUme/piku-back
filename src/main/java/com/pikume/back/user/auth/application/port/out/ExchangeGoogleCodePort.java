package com.pikume.back.user.auth.application.port.out;

import com.pikume.back.user.auth.application.dto.GoogleIdentity;

public interface ExchangeGoogleCodePort {
    GoogleIdentity exchangeCode(String code, String codeVerifier, String expectedNonce);
}
