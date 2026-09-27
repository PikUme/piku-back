package com.pikume.back.user.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_oauth_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_oauth_provider_subject", columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uk_oauth_user_provider", columnNames = {"user_id", "provider"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOAuthAccount {
    @Id
    @Column(length= 36)
    private String id;

    @Column(nullable= false, length= 36)
    private String userId;

    @Column(nullable= false, length= 20)
    private String provider;

    @Column(nullable= false, length= 255)
    private String providerSubject;

    @Column(nullable= false)
    private Instant linkedAt;

    public UserOAuthAccount(String userId, String provider, String subject, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = subject;
        this.linkedAt = now;
    }
}
