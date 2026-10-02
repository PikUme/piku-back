package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.auth.application.port.out.SignUpTransactionPort;
import com.pikume.back.user.domain.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SignUpTransactionAdapter implements SignUpTransactionPort {

	private final RecordUserAccountPort accounts;
	private final TransactionTemplate transaction;

	public SignUpTransactionAdapter(RecordUserAccountPort accounts, PlatformTransactionManager manager) {
		this.accounts = accounts;
		this.transaction = new TransactionTemplate(manager);
	}

	@Override
	public User register(User user) {
		return transaction.execute(status -> accounts.recordUserAccount(user));
	}
}
