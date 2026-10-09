package com.pikume.back.user.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.vo.Email;

import java.time.LocalDateTime;

@Entity
@Table(name = "verification")
@Getter
@NoArgsConstructor
public class Verification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VerificationType type;

	public Verification(String email, String code, VerificationType type, LocalDateTime expiresAt) {
		this.email = new Email(email).value();
		this.code = code;
		this.type = type;
		this.expiresAt = expiresAt;
	}

	public void updateCode(String newCode, LocalDateTime newExpiresAt) {
		this.code = newCode;
		this.expiresAt = newExpiresAt;
	}

	public boolean matches(String submittedCode, VerificationType submittedType) {
		return type == submittedType && code.equals(submittedCode);
	}
}
