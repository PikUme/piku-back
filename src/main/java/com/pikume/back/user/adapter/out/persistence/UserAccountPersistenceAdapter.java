package com.pikume.back.user.adapter.out.persistence;

import com.pikume.back.user.application.port.out.CheckUserUniquenessPort;
import com.pikume.back.user.application.port.out.LoadUserForAuthenticationPort;
import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.LoadUserForProfilePort;
import com.pikume.back.user.application.port.out.LoadUserReferencePort;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserAccountPersistenceAdapter implements LoadUserForProfilePort, LoadUserForAuthenticationPort,
		LoadUserForPasswordResetPort, LoadUserReferencePort, CheckUserUniquenessPort {

	private final UserJpaRepository jpaRepository;

	@Override
	public Optional<User> loadProfileUser(String userId) {
		return jpaRepository.findById(userId);
	}

	@Override
	public Optional<User> loadForLogin(String email) {
		return jpaRepository.findByEmail(new Email(email));
	}

	@Override
	public Optional<User> loadForSession(String userId) {
		return jpaRepository.findById(userId);
	}

	@Override
	public Optional<User> loadPasswordResetUser(String email) {
		return jpaRepository.findByEmail(new Email(email));
	}

	@Override
	public Optional<User> loadReference(String userId) {
		return jpaRepository.findById(userId);
	}

	@Override
	public List<User> loadReferences(Collection<String> userIds) {
		return jpaRepository.findAllById(userIds);
	}

	@Override
	public boolean isNicknameInUse(Nickname nickname) {
		return jpaRepository.existsByNickname(nickname);
	}

	@Override
	public boolean isEmailRegistered(String email) {
		return jpaRepository.existsByEmail(new Email(email));
	}
}
