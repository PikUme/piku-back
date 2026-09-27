package com.pikume.back.user.auth.adapter.out.persistence;

import com.pikume.back.user.auth.application.port.out.SignupTransactionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
        return transaction.execute(status -> work.get());
    }
}
