package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.exception.NicknameAlreadyExistsException;
import com.pikume.back.user.domain.exception.EmailAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 회원을 저장하고 이메일·닉네임 중복 제약 위반을 도메인 예외로 바꾸는 어댑터.
 */
@Repository
@RequiredArgsConstructor
public class UserPersistenceAdapter implements RecordUserAccountPort {

	/**
	 * users 테이블의 이메일 중복을 막는 고유 제약 이름.
	 */
	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk6dotkott2kjsp8vw4d0m25fb7";

	/**
	 * users 테이블의 닉네임 중복을 막는 고유 제약 이름.
	 */
	private static final String NICKNAME_UNIQUE_CONSTRAINT = "uk2ty1xmrrgtn89xt7kyxx6ta7h";

	/**
	 * MySQL이 고유 키 중복에 사용하는 오류 번호. 다른 SQL 오류는 메시지 분석 대상에서 제외한다.
	 */
	private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;

	/**
	 * MySQL 오류 메시지 끝의 {@code for key '제약 이름'} 구절만 추출하는 패턴.
	 * 중복된 입력값에 비슷한 구절이 들어 있어도 제약 이름으로 잘못 읽지 않도록 끝 위치를 확인한다.
	 */
	private static final Pattern MYSQL_DUPLICATE_KEY_NAME = Pattern.compile(
			"for key ['\\\"]([^'\\\"]+)['\\\"]\\s*$", Pattern.CASE_INSENSITIVE);

	private final UserJpaRepository jpaRepository;

	/**
	 * 회원을 저장하고 이메일·닉네임 중복을 각각의 도메인 예외로 알린다.
	 *
	 * <p>저장 쿼리를 즉시 실행해 트랜잭션 종료 전에 이 메서드에서 제약 위반을 처리한다.
	 * 이메일·닉네임 중복으로 구분할 수 없는 저장 오류는 그대로 전달한다.</p>
	 *
	 * @param user 저장할 회원
	 * @return 저장된 회원
	 * @throws NicknameAlreadyExistsException 이미 사용 중인 닉네임인 경우
	 * @throws EmailAlreadyExistsException 이미 가입된 이메일인 경우
	 * @throws DataIntegrityViolationException 이메일·닉네임 중복으로 구분할 수 없는 제약 위반인 경우
	 */
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

	/**
	 * 예외의 원인을 따라가며 저장에 실패한 제약 이름을 찾는다.
	 *
	 * <p>Hibernate가 전달한 이름이 이메일·닉네임 제약이면 우선 사용한다.
	 * 찾지 못하면 MySQL의 중복 키 오류(1062)에서 메시지 끝의 키 이름을 추출한다.
	 * SQL 메시지에서도 이름을 얻지 못하면 Hibernate가 전달한 이름을 유지한다.</p>
	 *
	 * @param throwable 회원 저장 중 발생한 예외
	 * @return 비교용으로 정리한 제약 이름. 이름이 없으면 빈 문자열
	 */
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
			if (current instanceof SQLException sqlException
					&& sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE) {
				String message = sqlException.getMessage();
				if (message == null) {
					continue;
				}
				Matcher matcher = MYSQL_DUPLICATE_KEY_NAME.matcher(message);
				String keyName = null;
				while (matcher.find()) {
					keyName = matcher.group(1);
				}
				if (keyName != null) {
					int qualifierSeparator = keyName.lastIndexOf('.');
					return normalize(qualifierSeparator >= 0
							? keyName.substring(qualifierSeparator + 1)
							: keyName);
				}
			}
		}
		return reportedConstraintName;
	}

	/**
	 * 이메일·닉네임 중복 처리에 사용하는 회원 제약인지 확인한다.
	 *
	 * @param constraintName 비교용으로 정리한 제약 이름
	 * @return 이메일 또는 닉네임 제약이면 {@code true}
	 */
	private boolean isKnownUserConstraint(String constraintName) {
		return isNicknameConstraint(constraintName) || isEmailConstraint(constraintName);
	}

	/**
	 * Hibernate가 전달한 제약 이름을 비교하기 쉬운 형태로 정리한다.
	 *
	 * <p>백틱·큰따옴표·공백을 제거하고 소문자로 바꾼다.
	 * 이름 앞의 테이블·스키마 접두어와 H2가 붙이는 {@code _INDEX_숫자} 접미사도 제거한다.</p>
	 *
	 * @param constraintName Hibernate가 전달한 제약 이름
	 * @return 비교용으로 정리한 제약 이름. 입력이 {@code null}이면 빈 문자열
	 */
	private String normalizeReportedConstraintName(String constraintName) {
		String normalized = normalize(constraintName);
		int qualifierSeparator = normalized.lastIndexOf('.');
		if (qualifierSeparator >= 0) {
			normalized = normalized.substring(qualifierSeparator + 1);
		}
		return normalized.replaceAll("_index_\\d+$", "");
	}

	/**
	 * 닉네임 고유 제약 또는 H2의 닉네임 제약 표기와 정확히 일치하는지 확인한다.
	 *
	 * @param constraintName 비교용으로 정리한 제약 이름
	 * @return 닉네임 제약이면 {@code true}
	 */
	private boolean isNicknameConstraint(String constraintName) {
		return constraintName.equals(NICKNAME_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(nickname)");
	}

	/**
	 * 이메일 고유 제약 또는 H2의 이메일 제약 표기와 정확히 일치하는지 확인한다.
	 *
	 * @param constraintName 비교용으로 정리한 제약 이름
	 * @return 이메일 제약이면 {@code true}
	 */
	private boolean isEmailConstraint(String constraintName) {
		return constraintName.equals(EMAIL_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(email)");
	}

	/**
	 * 제약 이름을 소문자로 바꾸고 백틱·큰따옴표·공백을 제거한다.
	 *
	 * @param constraintName 원래 제약 이름
	 * @return 비교용으로 정리한 제약 이름. 입력이 {@code null}이면 빈 문자열
	 */
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
