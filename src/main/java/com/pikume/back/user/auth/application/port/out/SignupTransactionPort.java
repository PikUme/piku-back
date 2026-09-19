package com.pikume.back.user.auth.application.port.out;

import java.util.function.Supplier;
public interface SignupTransactionPort {
    <T> T required(Supplier<T> work);
}
