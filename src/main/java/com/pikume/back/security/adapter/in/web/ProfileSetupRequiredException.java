package com.pikume.back.security.adapter.in.web;

import org.springframework.security.access.AccessDeniedException;

public class ProfileSetupRequiredException extends AccessDeniedException {
    public ProfileSetupRequiredException() { super("닉네임과 캐릭터 설정을 완료해주세요."); }
}
