package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationJpaRepository extends JpaRepository<Verification, Long> {

	Optional<Verification> findByEmailAndType(String email, VerificationType type);
}
