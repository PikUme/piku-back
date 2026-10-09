package com.pikume.back.user.application.port.out;

import java.util.function.Supplier;

public interface NicknameWriteTransactionPort {

	<T> T execute(Supplier<T> operation);
}
