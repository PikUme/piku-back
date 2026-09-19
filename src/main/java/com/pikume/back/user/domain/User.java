package com.pikume.back.user.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.pikume.back.global.entity.BaseEntity;
import com.pikume.back.user.domain.exception.InvalidNicknameException;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "users", uniqueConstraints = {
		@UniqueConstraint(name = "UK6dotkott2kjsp8vw4d0m25fb7", columnNames = "email"),
		@UniqueConstraint(name = "UK2ty1xmrrgtn89xt7kyxx6ta7h", columnNames = "nickname")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {
	private static final String TEMPORARY_NICKNAME_PREFIX = "가입대기_";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(length = 36)
	private String id;

	@Column(nullable = false)
	@Getter(AccessLevel.NONE)
	private Email email;

	private String password;

	@Column(nullable = false)
	@Getter(AccessLevel.NONE)
	private Nickname nickname;

	@Column(name = "character_id", nullable = false)
	private Long characterId;

	@Enumerated(EnumType.STRING)
	@Column(name = "profile_setup_status", nullable = false, length = 20)
	private ProfileSetupStatus profileSetupStatus = ProfileSetupStatus.COMPLETED;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public User(String email, String password, String nickname, Long characterId) {
		this(email, password, new Nickname(nickname), characterId);
	}

	public User(String email, String password, Nickname nickname, Long characterId) {
		this.email = new Email(email);
		this.password = password;
		this.nickname = requireNickname(nickname);
		this.characterId = requireCharacterId(characterId);
	}

	public User(String id, String email, String password, String newNickname, Long characterId) {
		this.id = id;
		this.email = new Email(email);
		this.password = password;
		this.nickname = new Nickname(newNickname);
		this.characterId = requireCharacterId(characterId);
	}

	public static User pending(String email, String passwordHash, String temporaryNickname, Long characterId) {
		User user = new User(email, passwordHash, temporaryNickname, characterId);
		user.profileSetupStatus = ProfileSetupStatus.REQUIRED;
		return user;
	}

	/**
	 * 닉네임을 변경합니다.
	 * 
	 * @param newNickname 변경할 닉네임
	 */
	public void changeNickname(String newNickname) {
		changeNickname(new Nickname(newNickname));
	}

	public void changeNickname(Nickname newNickname) {
		this.nickname = requireFinalNickname(newNickname);
	}

	public void changeCharacter(Long characterId) {
		this.characterId = requireCharacterId(characterId);
	}

	public boolean isProfileSetupRequired() {
		return profileSetupStatus == ProfileSetupStatus.REQUIRED;
	}

	public void completeProfile(String nickname, Long characterId) {
		completeProfile(new Nickname(nickname), characterId);
	}

	public void completeProfile(Nickname nickname, Long characterId) {
		requireNickname(nickname);
		if (isWithdrawn()) {
			throw new IllegalStateException("탈퇴한 사용자는 프로필 설정을 완료할 수 없습니다.");
		}
		if (profileSetupStatus == ProfileSetupStatus.COMPLETED) {
			if (Objects.equals(getNickname(), nickname.value()) && Objects.equals(this.characterId, characterId)) {
				return;
			}
			throw new IllegalStateException("이미 완료된 프로필은 완료 요청으로 변경할 수 없습니다.");
		}

		Nickname completedNickname = requireFinalNickname(nickname);
		Long completedCharacterId = requireCharacterId(characterId);
		this.nickname = completedNickname;
		this.characterId = completedCharacterId;
		this.profileSetupStatus = ProfileSetupStatus.COMPLETED;
	}

	private Nickname requireFinalNickname(Nickname nickname) {
		requireNickname(nickname);
		if (nickname.value().startsWith(TEMPORARY_NICKNAME_PREFIX)) {
			throw new InvalidNicknameException("가입 대기 닉네임은 최종 닉네임으로 사용할 수 없습니다.");
		}
		return nickname;
	}

	public String getEmail() {
		return email == null ? null : email.value();
	}

	public String getNickname() {
		return nickname == null ? null : nickname.value();
	}

	private Long requireCharacterId(Long characterId) {
		if (characterId == null || characterId <= 0) {
			throw new IllegalArgumentException("캐릭터 식별자는 양수여야 합니다.");
		}
		return characterId;
	}

	private Nickname requireNickname(Nickname nickname) {
		if (nickname == null) {
			throw new InvalidNicknameException("닉네임은 필수 값입니다.");
		}
		return nickname;
	}

	/**
	 * 비밀번호를 변경하는 비즈니스 메서드
	 * 
	 * @param newHashedPassword 암호화된 새로운 비밀번호
	 */
	public void updatePassword(String newHashedPassword) {
		this.password = newHashedPassword;
	}

	public void withdraw() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isWithdrawn() {
		return deletedAt != null;
	}
}
