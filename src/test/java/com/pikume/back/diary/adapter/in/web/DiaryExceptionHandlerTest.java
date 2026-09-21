package com.pikume.back.diary.adapter.in.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.pikume.back.diary.application.exception.DiaryImageRelocationException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.pikume.back.diary.application.exception.DiaryAccessDeniedException;
import com.pikume.back.diary.application.exception.DiaryErrorCode;
import com.pikume.back.diary.application.exception.DiaryInvalidRequestException;
import com.pikume.back.diary.application.exception.DiaryNotFoundException;
import com.pikume.back.diary.application.exception.DuplicateDiaryException;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.exception.GlobalExceptionHandler;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("DiaryExceptionHandler")
class DiaryExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(
						new GlobalExceptionHandler(java.util.Optional.empty(), problemDetailFactory),
						new DiaryExceptionHandler(problemDetailFactory))
				.build();
	}

	@Test
	@DisplayName("DiaryNotFoundException은 diary 전용 Problem Details를 반환한다")
	void handlesDiaryNotFoundException() throws Exception {
		mockMvc.perform(get("/test/diary/not-found").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/not-found"))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	@DisplayName("AccessDeniedException은 diary forbidden Problem Details를 반환한다")
	void handlesAccessDeniedException() throws Exception {
		mockMvc.perform(get("/test/diary/forbidden").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/forbidden"))
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	@DisplayName("DiaryAccessDeniedException은 diary forbidden Problem Details를 반환한다")
	void handlesDiaryAccessDeniedException() throws Exception {
		mockMvc.perform(get("/test/diary/domain-forbidden").accept(MediaType.APPLICATION_JSON))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/forbidden"))
					.andExpect(jsonPath("$.status").value(403))
					.andExpect(jsonPath("$.detail").value(DiaryErrorCode.DIARY_ACCESS_DENIED.getMessage()));
	}

	@Test
	@DisplayName("DuplicateDiaryException은 diary conflict Problem Details를 반환한다")
	void handlesDuplicateDiaryException() throws Exception {
		LocalDate date = LocalDate.of(2026, 5, 21);

		mockMvc.perform(get("/test/diary/conflict").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/conflict"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value(DiaryErrorCode.DUPLICATE_DIARY.format(date)));
	}

	@Test
	@DisplayName("DiaryInvalidRequestException은 diary invalid-request Problem Details를 반환한다")
	void handlesDiaryInvalidRequestException() throws Exception {
		mockMvc.perform(get("/test/diary/invalid-request").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/invalid-request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value("일기 내용은 비어 있을 수 없습니다."));
	}

	@Test
	@DisplayName("EntityNotFoundException은 diary not-found Problem Details를 반환한다")
	void handlesEntityNotFoundException() throws Exception {
		mockMvc.perform(get("/test/diary/entity-not-found").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://api.pikume.com/problems/diary/not-found"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("엔티티를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("일기 서버 오류는 경계에서 한 번 기록하고 예외 메시지는 노출하지 않는다")
	void logsServerFailureOnceWithoutExceptionMessage() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(DiaryExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			mockMvc.perform(get("/test/diary/not-found")).andExpect(status().isNotFound());
			mockMvc.perform(get("/test/diary/relocation-failed"))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.status").value(500));
		} finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage())
					.contains("reason=DIARY_IMAGE_RELOCATION_FAILED", "status=500")
					.doesNotContain("PRIVATE-RELOCATION-FAILURE", "PRIVATE-STORAGE-FAILURE");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@RestController
	static class TestController {
		@GetMapping("/test/diary/relocation-failed")
		String relocationFailed() {
			throw new DiaryImageRelocationException("PRIVATE-RELOCATION-FAILURE",
					new IllegalStateException("PRIVATE-STORAGE-FAILURE"));
		}

		@GetMapping("/test/diary/not-found")
		String notFound() {
			throw new DiaryNotFoundException();
		}

		@GetMapping("/test/diary/forbidden")
		String forbidden() {
			throw new AccessDeniedException("denied");
		}

		@GetMapping("/test/diary/domain-forbidden")
		String domainForbidden() {
			throw new DiaryAccessDeniedException();
		}

		@GetMapping("/test/diary/conflict")
		String conflict() {
			throw new DuplicateDiaryException(LocalDate.of(2026, 5, 21));
		}

		@GetMapping("/test/diary/invalid-request")
		String invalidRequest() {
			throw new DiaryInvalidRequestException("일기 내용은 비어 있을 수 없습니다.");
		}

		@GetMapping("/test/diary/entity-not-found")
		String entityNotFound() {
			throw new EntityNotFoundException("missing");
		}
	}
}
