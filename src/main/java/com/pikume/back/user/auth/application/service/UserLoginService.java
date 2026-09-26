package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.port.in.QueryUserIdentityUseCase;
import com.pikume.back.user.auth.application.dto.LoginCommand;
import com.pikume.back.user.auth.application.dto.LoginResult;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.port.in.LoginUseCase;
import com.pikume.back.user.auth.application.port.in.IssueUserSessionUseCase;
import com.pikume.back.user.auth.application.port.out.AuthenticationTokenPort;
import com.pikume.back.user.auth.application.port.out.PasswordProtectionPort;
import com.pikume.back.user.auth.application.port.out.RefreshSessionPort;
import com.pikume.back.user.domain.exception.InvalidEmailException;
import com.pikume.back.user.domain.vo.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserLoginService implements LoginUseCase {

	private final QueryUserIdentityUseCase queryUserIdentityUseCase;
	private final PasswordProtectionPort passwordProtectionPort;
	private final IssueUserSessionUseCase issueUserSessionUseCase;

	@Override
	public LoginResult login(LoginCommand command) {
		validateEmail(command.email());
		var user = queryUserIdentityUseCase.queryUserIdentityByEmail(command.email())
				.orElseThrow(InvalidCredentialsException::new);
		if (command.password() == null || user.passwordHash() == null ||
				!passwordProtectionPort.matches(command.password(), user.passwordHash())) {
			throw new InvalidCredentialsException();
		}
		LoginResult result = issueUserSessionUseCase.issueSession(user.id(), command.deviceId());
		log.info("event=login_completed outcome=success userId={}", user.id());
		return result;
	}

	private void validateEmail(String email) {
		try {
			new Email(email);
		} catch (InvalidEmailException exception) {
			throw new InvalidCredentialsException();
		}
	}
}
