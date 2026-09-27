package com.pikume.back.user.auth.application.dto;

public record SignupAgreementDocument(String type, String version, String content, boolean required) {
}
