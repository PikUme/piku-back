package com.pikume.back.notification.adapter.out.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.pikume.back.notification.application.port.out.DeliverPushNotificationPort;

@Component
@Profile("!prod")
@Slf4j
public class LocalPushAdapter implements DeliverPushNotificationPort {

	@Override
	public void deliverPushNotification(String targetToken, String body) {
		log.debug("event=local_push_delivery outcome=skipped reason=non_production");
	}
}
