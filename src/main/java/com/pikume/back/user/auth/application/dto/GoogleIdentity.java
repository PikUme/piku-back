package com.pikume.back.user.auth.application.dto;

/** Verified provider subject and separate email ownership facts. */
public record GoogleIdentity(String subject, String email, boolean emailVerified, boolean emailAuthoritative) {}
