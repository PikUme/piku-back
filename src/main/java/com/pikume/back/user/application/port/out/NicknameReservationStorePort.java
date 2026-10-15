package com.pikume.back.user.application.port.out;

import com.pikume.back.user.application.dto.NicknameReservationResult;

import java.util.Optional;

public interface NicknameReservationStorePort {

	NicknameReservationResult reserve(String ownerKey, String nicknameKey, String nickname);

	Optional<NicknameReservationResult> load(String ownerKey);

	boolean isHeldBy(String nicknameKey, String ownerKey);

	boolean isReservedByOther(String nicknameKey, String ownerKey);

	boolean releaseIfVersionMatches(String ownerKey, String nicknameKey, String version);
}
