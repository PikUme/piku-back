package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VerificationJpaRepository extends JpaRepository<Verification, Long> {

	@Query("select v from Verification v where v.email = :email and v.type = :type and v.emailVerificationId is null")
	Optional<Verification> findByEmailAndType(
			@Param("email")
			String email,
			@Param("type")
			VerificationType type);
}
