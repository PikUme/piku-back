package com.pikume.back.user.auth.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import com.pikume.back.user.auth.domain.Verification;
import com.pikume.back.user.auth.domain.vo.VerificationType;

import java.util.Optional;

public interface VerificationJpaRepository extends JpaRepository<Verification, Long> {

	@org.springframework.data.jpa.repository.Query("select v from Verification v where v.email = :email and v.type = :type and v.challengeId is null")
    Optional<Verification> findByEmailAndType(@org.springframework.data.repository.query.Param("email") String email,
            @org.springframework.data.repository.query.Param("type") VerificationType type);
}
