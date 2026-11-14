package com.pikume.back.creative.application.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiaryIllustrationPromptPolicy")
class DiaryIllustrationPromptPolicyTest {

	private final DiaryIllustrationPromptPolicy policy = new DiaryIllustrationPromptPolicy();

	@Nested
	@DisplayName("createPrompt")
	class CreatePrompt {

		@Test
		@DisplayName("Gemini 프롬프트 가이드의 서술형 문단과 Semantic Negative 원칙에 따라 부정 키워드와 일기장 연상 단어를 배제한다")
		void createsNarrativePromptWithoutNegativeOrDiaryKeywords() {
			String prompt = policy.createPrompt("오랜만에 여유로운 하루였다");

			assertThat(prompt).doesNotContain("diary");
			assertThat(prompt).doesNotContain("notebook");
			assertThat(prompt).doesNotContain("book");
			assertThat(prompt).doesNotContain("desk");
			assertThat(prompt).doesNotContain("do not");
			assertThat(prompt).doesNotContain("Render no text");
			assertThat(prompt).contains("Create a single narrative illustration featuring the exact same character");
			assertThat(prompt).contains("cinematic medium shot or wide-angle environmental view");
			assertThat(prompt).contains("오랜만에 여유로운 하루였다");
		}

		@Test
		@DisplayName("디자인 텍스트 의도가 없으면 긍정문 기반의 무지 질감(clean, unlabelled textures) 묘사를 포함한다")
		void createPromptWithoutDesignTextIntent() {
			String prompt = policy.createPrompt("공원에서 산책을 했다");

			assertThat(prompt).contains("clean, unlabelled textures and pure visual illustration details");
			assertThat(prompt).contains("공원에서 산책을 했다");
		}

		@Test
		@DisplayName("디자인 텍스트 의도가 있으면 의도된 디자인 요소 내 짧은 텍스트 허용 문장을 포함한다")
		void createPromptWithDesignTextIntent() {
			String prompt = policy.createPrompt("포스터가 붙어있는 카페에 앉아 있었다");

			assertThat(prompt).contains("Include short, legible text only on intentional scene elements");
			assertThat(prompt).contains("포스터가 붙어있는 카페에 앉아 있었다");
		}
	}
}


