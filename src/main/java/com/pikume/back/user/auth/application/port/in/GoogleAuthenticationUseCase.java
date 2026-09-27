package com.pikume.back.user.auth.application.port.in;
import com.pikume.back.user.auth.application.dto.*;
public interface GoogleAuthenticationUseCase {
    GoogleAuthorizationStart startWeb(String callerBinding, String deviceId, String targetUserId, String reauthenticationPassword, String requestOriginKey);
    GoogleAuthenticationResult completeWeb(String state, String code, String callerBinding);
    GoogleNativeChallenge startMobile(String registration, String callerBinding, String deviceId, String targetUserId, String reauthenticationPassword, String requestOriginKey);
    GoogleAuthenticationResult completeMobile(String state, String idToken, String callerBinding);
    void failWeb(String state, String callerBinding);
}
