package com.pikume.back.user.auth.application.port.out;

import java.util.function.Supplier;
public interface EmailVerificationTransactionPort {
    <T> T required(Supplier<T> work);
}
