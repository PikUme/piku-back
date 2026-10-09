package com.pikume.back.user.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.vo.Email;
import com.pikume.back.user.domain.vo.Nickname;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * User JPA Repository (Spring Data JPA 인터페이스)
 */
public interface UserJpaRepository extends JpaRepository<User, String> {

	interface DailyCountProjection {
		Object getMetricDate();

		Long getMetricCount();
	}

	Optional<User> findByEmail(Email email);

	boolean existsByEmail(Email email);

	long countByDeletedAtIsNull();

	long countByCreatedAtBefore(LocalDateTime cutoffExclusive);

	@Query(value = """
			SELECT CAST(created_at AS DATE) AS metricDate, COUNT(*) AS metricCount
			FROM users
			WHERE created_at >= :startDateTime
			  AND created_at < :endExclusiveDateTime
			  AND deleted_at IS NULL
			GROUP BY CAST(created_at AS DATE)
			""", nativeQuery = true)
	List<DailyCountProjection> countSignupMembersByDate(
			@Param("startDateTime") LocalDateTime startDateTime,
			@Param("endExclusiveDateTime") LocalDateTime endExclusiveDateTime);

	@Query(value = """
			SELECT CAST(created_at AS DATE) AS metricDate, COUNT(*) AS metricCount
			FROM users
			WHERE created_at >= :startDateTime
			  AND created_at < :endExclusiveDateTime
			GROUP BY CAST(created_at AS DATE)
			""", nativeQuery = true)
	List<DailyCountProjection> countAllSignupMembersByDate(
			@Param("startDateTime") LocalDateTime startDateTime,
			@Param("endExclusiveDateTime") LocalDateTime endExclusiveDateTime);

	@Query(value = "SELECT * FROM users WHERE nickname LIKE :keyword AND deleted_at IS NULL", nativeQuery = true)
	Page<User> searchByName(@Param("keyword") String keyword, Pageable pageable);

	boolean existsByNickname(Nickname nickname);

	@Modifying
	@Transactional
	@Query(value = "UPDATE users SET password = :passwordHash, updated_at = CURRENT_TIMESTAMP(6) WHERE id = :userId", nativeQuery = true)
	int updatePasswordOnly(@Param("userId") String userId, @Param("passwordHash") String passwordHash);
}
