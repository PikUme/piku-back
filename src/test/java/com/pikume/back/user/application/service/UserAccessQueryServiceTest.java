package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.UserAccessProfileStatus;
import com.pikume.back.user.application.dto.UserAccessView;
import com.pikume.back.user.application.port.out.QueryUserAccessPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserAccessQueryService")
class UserAccessQueryServiceTest {

	@Test
	@DisplayName("사용자 접근 상태 조회를 전용 조회 포트에 위임한다")
	void queriesNarrowUserAccessView() {
		UserAccessView expected = new UserAccessView(
				"user-1", false, UserAccessProfileStatus.REQUIRED);
		QueryUserAccessPort port = userId -> Optional.of(expected);
		UserAccessQueryService service = new UserAccessQueryService(port);

		assertThat(service.queryUserAccess("user-1")).contains(expected);
	}
}
