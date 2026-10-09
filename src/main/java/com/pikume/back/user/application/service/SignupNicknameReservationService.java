package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.NicknameReservationResult;
import com.pikume.back.user.application.exception.NicknameReservationConflictException;
import com.pikume.back.user.application.exception.NicknameReservationTokenInvalidException;
import com.pikume.back.user.application.exception.SignupEmailAlreadyExistsException;
import com.pikume.back.user.application.port.in.ReserveSignupNicknameUseCase;
import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.NicknameIdentityPort;
import com.pikume.back.user.application.port.out.NicknameReservationStorePort;
import com.pikume.back.user.application.port.out.NicknameWriteTransactionPort;
import com.pikume.back.user.auth.application.port.out.EmailVerificationStorePort;
import com.pikume.back.user.auth.application.exception.EmailVerificationException;
import com.pikume.back.user.auth.application.exception.EmailVerificationFailure;
import com.pikume.back.user.domain.exception.InvalidEmailException;
import com.pikume.back.user.auth.application.service.EmailVerificationService;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SignupNicknameReservationService implements ReserveSignupNicknameUseCase {

	private final EmailVerificationStorePort emailVerificationStorePort;
	private final CheckUserUniquenessPort checkUserUniquenessPort;
	private final NicknameIdentityPort nicknameIdentityPort;
	private final NicknameReservationStorePort reservationStorePort;
	private final NicknameWriteTransactionPort writeTransactionPort;

	@Override
	public NicknameReservationResult reserve(String rawEmail, String emailVerificationToken, String rawNickname) {
		String email;
		try {
			email = new Email(rawEmail).value().toLowerCase(Locale.ROOT);
		} catch (InvalidEmailException exception) {
			throw new EmailVerificationException(EmailVerificationFailure.INVALID_EMAIL);
		}
		Nickname nickname = new Nickname(rawNickname);
		String emailKey = EmailVerificationService.hash(email);
		String tokenHash = EmailVerificationService.hash(emailVerificationToken);
		if (emailVerificationStorePort.loadProof(emailKey, tokenHash).isEmpty()) {
			throw new NicknameReservationTokenInvalidException();
		}
		String ownerKey = "signup:" + emailKey;
		String nicknameKey = nicknameIdentityPort.keyFor(nickname);

		return writeTransactionPort.execute(() -> {
			if (checkUserUniquenessPort.isEmailRegistered(email)) {
				throw new SignupEmailAlreadyExistsException();
			}
			if (checkUserUniquenessPort.isNicknameInUse(nickname)) {
				throw new NicknameReservationConflictException();
			}
			return reservationStorePort.reserve(ownerKey, nicknameKey, nickname.value());
		});
	}
}
