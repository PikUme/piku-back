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
import java.sql.SQLException;
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
	private static final Pattern MYSQL_DUPLICATE_KEY = Pattern.compile(
			"for key\\s+['`\\\"]([^'`\\\"]+)['`\\\"]", Pattern.CASE_INSENSITIVE);

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
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ConstraintViolationException constraintViolation) {
				String constraintName = normalize(constraintViolation.getConstraintName());
				if (!constraintName.isBlank()) {
					return constraintName;
				}
				SQLException sqlException = constraintViolation.getSQLException();
				if (sqlException == null || sqlException.getErrorCode() != 1062) {
					continue;
				}
				Matcher key = MYSQL_DUPLICATE_KEY.matcher(constraintViolation.getMessage());
				if (key.find()) {
					String identifier = normalize(key.group(1));
					int separator = identifier.lastIndexOf('.');
					String indexName = separator < 0 ? identifier : identifier.substring(separator + 1);
					if (indexName.equals(EMAIL_UNIQUE_CONSTRAINT)
							|| indexName.equals(NICKNAME_UNIQUE_CONSTRAINT)) {
						return indexName;
					}
				}
			}
		}
		return "";
	}

	private boolean isNicknameConstraint(String constraintName) {
		return constraintName.contains(NICKNAME_UNIQUE_CONSTRAINT)
				|| constraintName.contains("users(nickname");
	}

	private boolean isEmailConstraint(String constraintName) {
		return constraintName.contains(EMAIL_UNIQUE_CONSTRAINT)
				|| constraintName.contains("users(email");
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
