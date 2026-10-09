package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.exception.NicknameReservationUnavailableException;
import com.pikume.back.user.application.port.out.NicknameWriteTransactionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class MySqlNicknameWriteTransactionAdapter implements NicknameWriteTransactionPort {

	private final JdbcTemplate jdbcTemplate;
	private final org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Override
	public <T> T execute(Supplier<T> operation) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		transaction.setTimeout(5);
		try {
			return transaction.execute(status -> {
				try {
					Long mutex = jdbcTemplate.queryForObject(
							"SELECT id FROM nickname_write_mutex WHERE id = 1 FOR UPDATE", Long.class);
					if (mutex == null) throw new IllegalStateException("Nickname write mutex row is missing");
				} catch (DataAccessException exception) {
					throw new NicknameReservationUnavailableException("write_coordination", exception);
				}
				try {
					return operation.get();
				} catch (DataAccessException exception) {
					throw new NicknameReservationUnavailableException("write_operation", exception);
				}
			});
		} catch (TransactionException exception) {
			throw new NicknameReservationUnavailableException("write_transaction", exception);
		}
	}
}
