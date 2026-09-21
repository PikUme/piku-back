package com.pikume.back.social.adapter.in.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.pikume.back.social.adapter.in.web.dto.CommentRequestDto;
import com.pikume.back.social.adapter.in.web.dto.CommentUpdateDto;
import com.pikume.back.social.application.dto.CommentResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import com.pikume.back.security.principal.UserPrincipal;
import com.pikume.back.global.error.ProblemDetailFactory;
import com.pikume.back.global.exception.GlobalExceptionHandler;
import com.pikume.back.social.adapter.in.web.problem.SocialProblemType;
import com.pikume.back.social.application.exception.SocialErrorCode;
import com.pikume.back.social.application.exception.SocialException;
import com.pikume.back.social.application.port.in.CreateCommentUseCase;
import com.pikume.back.social.application.port.in.DeleteCommentUseCase;
import com.pikume.back.social.application.port.in.QueryCommentPageUseCase;
import com.pikume.back.social.application.port.in.UpdateCommentUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentController")
class CommentControllerTest {

	@InjectMocks
	private CommentController commentController;

	@Mock
	private CreateCommentUseCase createCommentUseCase;
	@Mock
	private UpdateCommentUseCase updateCommentUseCase;
	@Mock
	private DeleteCommentUseCase deleteCommentUseCase;
	@Mock
	private QueryCommentPageUseCase queryCommentPageUseCase;

	private MockMvc mockMvc;
	private UserPrincipal userDetails;
	private final ProblemDetailFactory problemDetailFactory = new ProblemDetailFactory();

	@BeforeEach
	void setUp() {
		userDetails = new UserPrincipal("viewer-id", "viewer");
		mockMvc = MockMvcBuilders.standaloneSetup(commentController)
				.setCustomArgumentResolvers(
						new AuthenticationPrincipalResolver(userDetails),
						new PageableHandlerMethodArgumentResolver())
				.setControllerAdvice(
						new GlobalExceptionHandler(java.util.Optional.empty(), problemDetailFactory),
						new SocialExceptionHandler(problemDetailFactory))
				.build();
	}

	@Test
	@DisplayName("GET /api/comments는 비공개 일기 접근 시 404를 반환한다")
	void getRootCommentsReturnsNotFoundWhenDiaryIsHidden() throws Exception {
		given(queryCommentPageUseCase.queryRootCommentPage(eq(1L), any(), eq("viewer-id")))
				.willThrow(new SocialException(SocialErrorCode.DIARY_NOT_FOUND));

		mockMvc.perform(get("/api/comments")
						.param("diaryId", "1")
						.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value(SocialProblemType.DIARY_NOT_FOUND.type().toString()))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value(SocialErrorCode.DIARY_NOT_FOUND.message()));
	}

	@Test
	@DisplayName("댓글 본문은 기록하지 않고 변경 성공 시에만 INFO를 남긴다")
	void logsSuccessfulChangesWithoutCommentContent() {
		String privateContent = "PRIVATE-COMMENT-CONTENT";
		String privateUpdate = "PRIVATE-UPDATED-CONTENT";
		String privateFailure = "PRIVATE-FAILED-CONTENT";
		given(createCommentUseCase.createComment(1L, privateContent, null, "viewer-id"))
				.willReturn(new CommentResult(2L, privateContent, LocalDateTime.now()));
		given(updateCommentUseCase.updateComment(2L, privateUpdate, "viewer-id"))
				.willReturn(new CommentResult(2L, privateUpdate, LocalDateTime.now()));
		given(updateCommentUseCase.updateComment(2L, privateFailure, "viewer-id"))
				.willThrow(new SocialException(SocialErrorCode.DIARY_NOT_FOUND));
		Logger logger = (Logger) LoggerFactory.getLogger(CommentController.class);
		Level previousLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			commentController.createComment(new CommentRequestDto(1L, privateContent, null), userDetails);
			commentController.updateComment(2L, new CommentUpdateDto(privateUpdate), userDetails);
			assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.INFO)).hasSize(2);
			int successfulLogCount = appender.list.size();

			assertThatThrownBy(() -> commentController.updateComment(
					2L, new CommentUpdateDto(privateFailure), userDetails))
					.isInstanceOf(SocialException.class);
			assertThat(appender.list).hasSize(successfulLogCount);
			assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
					.noneMatch(message -> message.contains(privateContent)
							|| message.contains(privateUpdate) || message.contains(privateFailure));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
		}
	}

	private record AuthenticationPrincipalResolver(UserPrincipal userDetails) implements HandlerMethodArgumentResolver {
		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return parameter.getParameterType().equals(UserPrincipal.class);
		}

		@Override
		public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
				NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
			return userDetails;
		}
	}
}
