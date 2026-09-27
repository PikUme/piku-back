package com.pikume.back.user.auth.application.service;

import com.pikume.back.user.application.port.out.LoadUserForPasswordResetPort;
import com.pikume.back.user.application.port.out.RecordUserAccountPort;
import com.pikume.back.user.auth.application.dto.ResetPasswordCommand;
import com.pikume.back.user.auth.application.exception.InvalidCredentialsException;
import com.pikume.back.user.auth.application.port.out.*;
import com.pikume.back.user.auth.domain.VerifiedEmail;
import com.pikume.back.user.auth.domain.service.EmailVerificationPolicy;
import com.pikume.back.user.auth.domain.vo.VerificationType;
import com.pikume.back.user.domain.User;
import com.pikume.back.user.domain.service.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PasswordResetSafetyTest {
    @Mock LoadUserForPasswordResetPort users;
    @Mock LoadCompletedEmailVerificationPort verifications;
    @Mock RecordCompletedEmailVerificationPort recordVerification;
    @Mock RecordUserAccountPort records;
    @Mock PasswordProtectionPort passwords;
    @Spy PasswordPolicy policy = new PasswordPolicy();
    @Spy EmailVerificationPolicy verificationPolicy = new EmailVerificationPolicy();
    @InjectMocks AuthService service;
    @Test void verifiedEmailCannotAddPasswordToSocialOnlyUser() { reject(new User("user@gmail.com",null,"nick",1L)); }
    @Test void verifiedEmailCannotResetWithdrawnUser() {
        User user = new User("user@gmail.com","old","nick",1L);
        user.withdraw();
        reject(user);
    }
    private void reject(User user) {
        given(users.loadPasswordResetUser("user@gmail.com")).willReturn(Optional.of(user));
        lenient().when(verifications.loadLatestVerification("user@gmail.com",VerificationType.PASSWORD_RESET))
            .thenReturn(Optional.of(new VerifiedEmail("user@gmail.com",VerificationType.PASSWORD_RESET)));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordCommand("user@gmail.com","newPwd@1")))
            .isInstanceOf(InvalidCredentialsException.class);
        then(records).shouldHaveNoInteractions();
        then(passwords).shouldHaveNoInteractions();
    }
}
