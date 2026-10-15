package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.NicknameIdentityPort;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MySqlNicknameIdentityAdapter implements NicknameIdentityPort {

	private static final String NICKNAME_WEIGHT_QUERY = """
			SELECT HEX(WEIGHT_STRING(
				CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci))
			""";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public String keyFor(Nickname nickname) {
		String key = jdbcTemplate.queryForObject(NICKNAME_WEIGHT_QUERY, String.class, nickname.value());
		if (key == null) {
			throw new IllegalStateException("MySQL returned an empty nickname collation key");
		}
		return key;
	}
}
