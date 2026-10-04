package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User Aggregate 저장 어댑터입니다.
 */
@Repository
@RequiredArgsConstructor
public class UserPersistenceAdapter implements RecordUserAccountPort {
	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk6dotkott2kjsp8vw4d0m25fb7";
	private static final String NICKNAME_UNIQUE_CONSTRAINT = "uk2ty1xmrrgtn89xt7kyxx6ta7h";
	private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;
	private static final Pattern MYSQL_DUPLICATE_KEY_NAME = Pattern.compile("for key ['\\\"]([^'\\\"]+)['\\\"]", Pattern.CASE_INSENSITIVE);

	private final UserJpaRepository jpaRepository;

	@Override
	public User recordUserAccount(User user) {
		try {
			return jpaRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			String constraintName = findConstraintName(exception);
			if (isNicknameConstraint(constraintName)) {
				throw new NicknameAlreadyExistsException(user.getNickname());
			}
			if (isEmailConstraint(constraintName)) {
				throw new EmailAlreadyExistsException();
			}
			throw exception;
		}
	}

	private String findConstraintName(Throwable throwable) {
		String reportedConstraintName = "";
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ConstraintViolationException constraintViolation) {
				String constraintName = normalizeReportedConstraintName(constraintViolation.getConstraintName());
				reportedConstraintName = constraintName;
				if (isKnownUserConstraint(constraintName)) {
					return constraintName;
				}
			}
		}

		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof java.sql.SQLException sqlException
					&& sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE) {
				Matcher matcher = MYSQL_DUPLICATE_KEY_NAME.matcher(sqlException.getMessage());
				if (matcher.find()) {
					String keyName = matcher.group(1);
					int qualifierSeparator = keyName.lastIndexOf('.');
					return normalize(qualifierSeparator >= 0
							? keyName.substring(qualifierSeparator + 1)
							: keyName);
				}
			}
		}
		return reportedConstraintName;
	}

	private boolean isKnownUserConstraint(String constraintName) {
		return isNicknameConstraint(constraintName) || isEmailConstraint(constraintName);
	}

	private String normalizeReportedConstraintName(String constraintName) {
		String normalized = normalize(constraintName);
		int qualifierSeparator = normalized.lastIndexOf('.');
		if (qualifierSeparator >= 0) {
			normalized = normalized.substring(qualifierSeparator + 1);
		}
		return normalized.replaceAll("_index_\\d+$", "");
	}

	private boolean isNicknameConstraint(String constraintName) {
		return constraintName.equals(NICKNAME_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(nickname)");
	}

	private boolean isEmailConstraint(String constraintName) {
		return constraintName.equals(EMAIL_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(email)");
	}

	private String normalize(String constraintName) {
		if (constraintName == null) {
			return "";
		}
		return constraintName.toLowerCase(Locale.ROOT)
				.replace("`", "")
				.replace("\"", "")
				.replaceAll("\\s+", "");
	}
}
