package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.exception.SignupFailure;
import com.pikume.back.user.auth.application.exception.SignupFlowException;
import com.pikume.back.user.auth.application.port.out.SignupTransactionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Locale;
import java.util.function.Supplier;

@Component
public class SignupTransactionAdapter implements SignupTransactionPort {
    private final TransactionTemplate transaction;
    public SignupTransactionAdapter(PlatformTransactionManager manager) {
        transaction=new TransactionTemplate(manager);
        // A failed write must finish before any retry; failed-code result commits before the public error is thrown.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (RuntimeException error) {
            for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if (!(cause instanceof org.hibernate.exception.ConstraintViolationException)) continue;
                String constraint=((org.hibernate.exception.ConstraintViolationException)cause).getConstraintName();
                String name=constraint==null?"":constraint.toLowerCase(Locale.ROOT);
                if (name.contains("uk6dotkott2kjsp8vw4d0m25fb7")) throw new SignupFlowException(SignupFailure.EMAIL_ALREADY_REGISTERED, error);
                if (name.contains("uk2ty1xmrrgtn89xt7kyxx6ta7h")) throw new SignupFlowException(SignupFailure.NICKNAME_COLLISION, error);
                if (name.contains("uk_oauth_provider_subject") || name.contains("uk_oauth_user_provider"))
                throw new SignupFlowException(SignupFailure.ACCOUNT_LINK_CONFLICT, error);
            }
            throw error;
        }
    }
}
