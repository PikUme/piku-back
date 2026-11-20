package com.pikume.back.global.port.out;

import com.pikume.back.global.email.OutboundEmailMessage;

public interface SendOutboundEmailPort {

	void send(OutboundEmailMessage message);
}
