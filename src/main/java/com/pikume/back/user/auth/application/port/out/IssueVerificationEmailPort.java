package com.pikume.back.user.auth.application.port.out;

public interface IssueVerificationEmailPort {

	String issueVerificationEmail(String email);

	void deliverVerificationCode(String email, String code);

}
