package com.pikume.back.user.application.port.out;

import com.pikume.back.user.domain.User;

/**
 * 사용자 계정 상태 기록 Outbound Port
 */
public interface RecordUserAccountPort {

	/**
	 * 변경된 사용자 계정 상태를 기록합니다.
	 */
	User recordUserAccount(User user);

	default void updatePasswordOnly(String userId, String passwordHash) {
		throw new UnsupportedOperationException("Password-only updates are not supported");
	}
}
