package com.pikume.back.user.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_agreements", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_agreement_version", columnNames = {"user_id", "agreement_type", "agreement_version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement {
    @Id
    @Column(length= 36)
    private String id;

    @Column(nullable= false, length= 36, updatable= false)
    private String userId;

    @Column(nullable= false, length= 50, updatable= false)
    private String agreementType;

    @Column(nullable= false, length= 50, updatable= false)
    private String agreementVersion;

    @Lob @Column(nullable= false, updatable= false, columnDefinition= "text")
    private String content;

    @Column(nullable= false, updatable= false)
    private boolean agreed;

    @Column(nullable= false, updatable= false)
    private Instant acceptedAt;

    public UserAgreement(String userId, String type, String version, String content, boolean agreed, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.agreementType = type;
        this.agreementVersion = version;
        this.content = content;
        this.agreed = agreed;
        this.acceptedAt = now;
    }
}
