package com.pikume.back.user.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class VerificationJpaRepositoryTest {

	@Autowired
	private VerificationJpaRepository repository;

	@Test
	void passwordResetLookupUsesTheExistingEmailAndTypeColumns() {
		Verification verification = new Verification(
				"reset@example.com", "123456", VerificationType.PASSWORD_RESET, LocalDateTime.now().plusMinutes(5));
		repository.saveAndFlush(verification);

		assertThat(repository.findByEmailAndType("reset@example.com", VerificationType.PASSWORD_RESET))
				.contains(verification);
	}
}
