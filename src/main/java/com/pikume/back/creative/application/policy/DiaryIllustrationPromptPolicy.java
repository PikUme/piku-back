package com.pikume.back.creative.application.policy;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DiaryIllustrationPromptPolicy {

	public String createPrompt(String content) {
		StringBuilder sb = new StringBuilder();

		sb.append("Create a single narrative illustration featuring the exact same character from the provided reference image, ");
		sb.append("preserving the character's facial features, hairstyle, skin tone, body proportions, signature outfit palette, and illustration art style. ");
		sb.append("Depict the character actively experiencing the main event and emotional atmosphere of the following story: \"")
				.append(content)
				.append("\". ");
		sb.append("Place the character directly inside the lived setting of the story, expressing the moment through a dynamic full-body or half-body pose, ");
		sb.append("vivid facial expression, and natural interaction with the surrounding environment. ");
		sb.append("Frame the composition as a cinematic medium shot or wide-angle environmental view with expressive lighting and color tones that match the mood and time of day. ");

		if (hasDesignTextIntent(content)) {
			sb.append("Include short, legible text only on intentional scene elements such as speech bubbles, signs, posters, banners, UI panels, or clothing prints.");
		} else {
			sb.append("Render all props, surfaces, and background elements with clean, unlabelled textures and pure visual illustration details.");
		}

		return sb.toString();
	}

	boolean hasDesignTextIntent(String content) {
		if (content == null || content.isBlank()) {
			return false;
		}
		String lower = content.toLowerCase(Locale.ROOT);
		return lower.contains("말풍선") || lower.contains("간판") || lower.contains("표지판")
				|| lower.contains("사인") || lower.contains("포스터") || lower.contains("배너")
				|| lower.contains("현수막") || lower.contains("간판문구")
				|| lower.contains("ui") || lower.contains("패널")
				|| lower.contains("티셔츠 프린트") || lower.contains("텍스트")
				|| lower.contains("speech bubble") || lower.contains("sign")
				|| lower.contains("poster") || lower.contains("banner") || lower.contains("caption");
	}
}


