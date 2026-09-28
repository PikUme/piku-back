package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.port.out.EmailVerificationTransactionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

@Component
public class EmailVerificationTransactionAdapter implements EmailVerificationTransactionPort {

	private final TransactionTemplate transaction;

	public EmailVerificationTransactionAdapter(PlatformTransactionManager manager) {
		transaction = new TransactionTemplate(manager);
		// 호출자와 별도 트랜잭션으로 실행해, 발송 제한과 오입력 횟수를 오류 응답 전에 저장한다.
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public <T> T required(Supplier<T> work) {
		return transaction.execute(status -> work.get());
	}
}
