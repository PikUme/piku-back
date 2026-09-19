package com.pikume.back.user.application.service;

import com.pikume.back.user.application.dto.UserAccessView;
import com.pikume.back.user.application.port.in.QueryUserAccessUseCase;
import com.pikume.back.user.application.port.out.QueryUserAccessPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccessQueryService implements QueryUserAccessUseCase {

	private final QueryUserAccessPort queryUserAccessPort;

	@Override
	public Optional<UserAccessView> queryUserAccess(String userId) {
		return queryUserAccessPort.queryUserAccess(userId);
	}
}
