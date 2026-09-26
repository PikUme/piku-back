package com.pikume.back.user.domain.service;

import com.pikume.back.user.domain.vo.Nickname;

/** Builds a display nickname from a verified email; account identity remains the full email. */
public final class DefaultSignupNicknamePolicy {

	private DefaultSignupNicknamePolicy() {}

	public static Nickname candidate(String verifiedEmail,int numericSuffix) {
		int separator=verifiedEmail.lastIndexOf('@');
		String localPart=separator<0?"":verifiedEmail.substring(0,separator).strip();
		if(localPart.isEmpty()) localPart="사용자";
		String suffix=numericSuffix==0?"":Integer.toString(numericSuffix);
		int end=Math.min(localPart.length(),20-suffix.length());
		if(end>0 && Character.isHighSurrogate(localPart.charAt(end-1))) end--;
		return new Nickname(localPart.substring(0,end)+suffix);
	}
}
