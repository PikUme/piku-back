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
 * 회원 저장 및 이메일·닉네임 중복 오류 처리
 */
@Repository
@RequiredArgsConstructor
public class UserPersistenceAdapter implements RecordUserAccountPort {

	/**
	 * users 테이블의 이메일 중복 방지 제약 이름
	 */
	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk6dotkott2kjsp8vw4d0m25fb7";

	/**
	 * users 테이블의 닉네임 중복 방지 제약 이름
	 */
	private static final String NICKNAME_UNIQUE_CONSTRAINT = "uk2ty1xmrrgtn89xt7kyxx6ta7h";

	/**
	 * MySQL 중복 키 오류 번호(1062)
	 * 다른 SQL 오류는 메시지 분석 대상에서 제외
	 */
	private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;

	/**
	 * MySQL 중복 키 오류의 제약 이름 추출 패턴
	 *
	 * <ul>
	 * <li>메시지 끝의 {@code for key '제약 이름'}만 추출</li>
	 * <li>입력값에 포함된 비슷한 문구를 제약 이름으로 잘못 읽는 문제 방지</li>
	 * </ul>
	 */
	private static final Pattern MYSQL_DUPLICATE_KEY_NAME = Pattern.compile(
			"for key ['\\\"]([^'\\\"]+)['\\\"]\\s*$", Pattern.CASE_INSENSITIVE);

	private final UserJpaRepository jpaRepository;

	/**
	 * 회원 저장 및 중복 오류 처리
	 *
	 * <ul>
	 * <li>{@code saveAndFlush}로 저장 쿼리 즉시 실행 및 중복 여부 확인</li>
	 * <li>이메일·닉네임 중복은 각각의 도메인 예외로 변환</li>
	 * <li>그 외 제약 위반은 원래 예외로 전달</li>
	 * </ul>
	 *
	 * @param user 저장할 회원
	 * @return 저장된 회원
	 * @throws NicknameAlreadyExistsException 이미 사용 중인 닉네임
	 * @throws EmailAlreadyExistsException 이미 가입된 이메일
	 * @throws DataIntegrityViolationException 이메일·닉네임 중복으로 구분할 수 없는 제약 위반
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
	 * 회원 저장 예외에서 제약 이름 조회
	 *
	 * <ul>
	 * <li>예외 원인에서 Hibernate의 이메일·닉네임 제약 이름 우선 확인</li>
	 * <li>판별 실패 시 MySQL 1062 오류의 마지막 {@code for key} 구절에서 이름 추출</li>
	 * <li>추출 실패 시 Hibernate가 전달한 제약 이름 유지</li>
	 * </ul>
	 *
	 * @param throwable 회원 저장 중 발생한 예외
	 * @return 비교용 제약 이름, 이름이 없으면 빈 문자열
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
	 * 이메일·닉네임 제약 여부 확인
	 *
	 * @param constraintName 비교용 제약 이름
	 * @return 이메일 또는 닉네임 제약이면 {@code true}
	 */
	private boolean isKnownUserConstraint(String constraintName) {
		return isNicknameConstraint(constraintName) || isEmailConstraint(constraintName);
	}

	/**
	 * Hibernate 제약 이름 정리
	 *
	 * <ul>
	 * <li>소문자로 변환 후 백틱·큰따옴표·공백 제거</li>
	 * <li>제약 이름 앞의 테이블·스키마 이름 제거</li>
	 * <li>H2의 {@code _INDEX_숫자} 접미사 제거</li>
	 * </ul>
	 *
	 * @param constraintName Hibernate가 전달한 제약 이름
	 * @return 비교용 제약 이름, 입력이 {@code null}이면 빈 문자열
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
	 * 닉네임 고유 제약 또는 H2의 {@code users(nickname)} 표기와 일치 여부 확인
	 *
	 * @param constraintName 비교용 제약 이름
	 * @return 닉네임 제약이면 {@code true}
	 */
	private boolean isNicknameConstraint(String constraintName) {
		return constraintName.equals(NICKNAME_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(nickname)");
	}

	/**
	 * 이메일 고유 제약 또는 H2의 {@code users(email)} 표기와 일치 여부 확인
	 *
	 * @param constraintName 비교용 제약 이름
	 * @return 이메일 제약이면 {@code true}
	 */
	private boolean isEmailConstraint(String constraintName) {
		return constraintName.equals(EMAIL_UNIQUE_CONSTRAINT)
				|| constraintName.equals("users(email)");
	}

	/**
	 * 제약 이름의 소문자 변환 및 백틱·큰따옴표·공백 제거
	 *
	 * @param constraintName 원래 제약 이름
	 * @return 비교용 제약 이름, 입력이 {@code null}이면 빈 문자열
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
