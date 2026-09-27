package com.pikume.back.user.auth.application.dto;

public record SignupAgreementCommand(String proof, String callerBinding, java.util.List<AgreementAcceptance> agreements) {
}
