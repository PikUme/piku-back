package com.pikume.back.user.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findByEmailForUpdate(@Param("email") Email email);

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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") String id);

	boolean existsByNickname(Nickname nickname);
}
