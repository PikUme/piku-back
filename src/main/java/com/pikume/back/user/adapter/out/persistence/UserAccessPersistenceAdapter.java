package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.dto.UserAccessProfileStatus;
import com.pikume.back.user.application.dto.UserAccessView;
import com.pikume.back.user.application.port.out.QueryUserAccessPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserAccessPersistenceAdapter implements QueryUserAccessPort {

	private final UserJpaRepository userJpaRepository;

	@Override
	public Optional<UserAccessView> queryUserAccess(String userId) {
		return userJpaRepository.findAccessById(userId)
				.map(row -> new UserAccessView(
						row.getId(),
						row.getDeletedAt() != null,
						UserAccessProfileStatus.from(row.getProfileSetupStatus())));
	}
}
