# Google 로그인 변경 계약

- Status: 구현 계약, 배포·활성화 전
- Audience: 프론트엔드·모바일 개발자
- Source of Truth: Yes
- Last Reviewed: 2026-09-19

## 범위와 공통 가입 흐름

Google 인증 시작·완료와 기존 계정 연결을 제공한다. 신규 Google 신원은 챕터형 가입의 공통 `progress`로 이어진다. 이메일 보완이 필요하면 `VERIFY_EMAIL`, 필수 동의가 필요하면 `AGREEMENTS`, 이미 생성된 미완료 회원은 `PROFILE`, 완료 회원은 `COMPLETE`다. 실제 회원 생성은 필수 동의 후이며 인증 성공만으로 일반 서비스를 초기화하지 않는다.

가입 API 기준 경로는 웹 `/api/auth/signup`, 모바일 `/api/mobile/auth/signup`이다. 진행 조회 `/progress`, 이메일 보완 `/email/code`·`/social/email`, 동의 `/agreements`, 닉네임 `/nickname`, 완료 `/profile` 계약은 선행 챕터형 가입과 같다. Google 전용 신규 회원에는 비밀번호가 없으며 비밀번호 재설정으로 비밀번호를 추가하지 않는다.

## Google 인증 API

Google 인증 API는 `/api/auth/signup`의 하위 경로가 아니다. 명시적 기존 계정 연결도 시작 요청의 `link: true`로 같은 엔드포인트를 사용한다.

| 대상·기능 | 메서드 | 엔드포인트 | 호출 방식 |
| --- | --- | --- | --- |
| 웹 Google 인증 시작 | POST | `/api/auth/oauth/google/start` | 프론트가 호출한 뒤 응답의 `authorizationUrl`로 이동 |
| 웹 Google 인증 콜백 | GET | `/api/auth/oauth/google/callback` | Google이 브라우저를 돌려보내는 서버 콜백. `state`와 `code` 또는 `error`를 query로 전달. 프론트가 직접 호출하는 API가 아님 |
| 모바일 Google 인증 준비 | POST | `/api/mobile/auth/oauth/google/challenge` | 앱이 호출해 서버의 `state`·`nonce`를 수신 |
| 모바일 Google 인증 완료 | POST | `/api/mobile/auth/oauth/google/complete` | 앱이 `state`와 Google `idToken`을 제출 |

## 웹 자격과 복귀

먼저 GET `/api/auth/signup/progress`를 쿠키 포함으로 호출한다. JSON `csrfToken`을 `X-Signup-CSRF`로 보내며 허용 Origin·호출자/CSRF 쿠키·`Device-Id`를 Google 시작 요청에 함께 보낸다. 쿠키는 host-only, Secure, HttpOnly, SameSite=Lax다. 명시적 연결은 Bearer도 필요하다. Google GET 콜백은 CSRF 헤더 대신 시작 때 결합한 state와 브라우저 쿠키를 검증한다.

웹의 성공 자격은 액세스 토큰의 `Authorization` 헤더와 `rn` HttpOnly 갱신 쿠키다. 가입 증명은 `__Host-pk-signup-proof` HttpOnly 쿠키로만 전달한다. 기존 Google 회원 로그인 성공은 이전 가입 증명을 정리하고, 새 Google 가입은 과거 갱신 쿠키를 정리한다. 호출자·CSRF 쿠키는 유지한다.

Google 로그인 버튼에서는 POST `/api/auth/oauth/google/start`에 `link: false`와 `Device-Id`를 보낸다. 응답 `authorizationUrl`로 이동한다. 서버 콜백은 설정된 프론트 복귀 주소로 303 이동하며 URL에 가입 증명·서비스 토큰을 넣지 않는다.

시작 본문은 `{"link": false}`이고 응답은 `{authorizationUrl: string, expiresAt: UTC 시각}`이다. state·nonce·PKCE는 서버가 생성하고 반환 URL에 필요한 값을 포함한다. 프론트가 URL을 재구성하거나 Google client secret을 취급하지 않는다. Google이 접근하는 백엔드 콜백 주소와 사용자가 최종 도착하는 프론트 복귀 주소는 서로 다르다.

복귀 시 이전 액세스 토큰을 재사용하지 말고 서버의 새 결과를 확인한다. 기존 회원이면 `rn`으로 POST `/api/auth/reissue` 후 GET `/api/auth/signup/progress`를 조회한다. 신규 가입이면 갱신 쿠키가 비워지고 가입 증명 쿠키가 있으므로, 토큰 없는 GET `/progress`에서 이메일 보완 또는 동의를 이어간다. 재발급의 401만으로 가입 증명까지 삭제하지 않는다.

프론트 복귀 화면은 다음 순서로 처리한다.

1. `oauthError`가 있으면 인증 실패를 표시하고 성공 화면으로 이동하지 않는다. 오류 URL을 정리하고 새 인증 시작을 제공한다. 취소·실패 시 기존 쿠키가 남을 수 있으므로 기존 로그인 상태를 새 Google 인증 성공으로 해석하지 않는다.
2. 오류가 없으면 이전 액세스 토큰을 요청에 붙이지 않고 POST `/api/auth/reissue`를 쿠키 포함으로 호출한다. 리다이렉트 응답의 `Authorization` 헤더를 화면 JavaScript에서 읽으려 하지 않는다.
3. 재발급 200이면 응답 `Authorization: Bearer …`를 저장하고 그 토큰으로 GET `/api/auth/signup/progress`와 필요 시 GET `/api/auth/me`를 조회한다.
4. 재발급 401이면 로그인 토큰 없이 GET `/api/auth/signup/progress`를 호출한다. `VERIFY_EMAIL`은 이메일 보완, `AGREEMENTS`는 동의, `AUTHENTICATE`는 인증 시작 화면으로 이동한다. 5xx나 네트워크 실패를 신규 가입으로 단정하지 말고 복구 재시도를 제공한다.
5. `PROFILE`이면 제한된 가입 프로필 화면, `COMPLETE`이면 일반 서비스로 이동한다. 인증된 진행 조회는 Bearer 회원 상태가 가입 증명보다 우선한다. 새 Google 가입을 복구할 때 과거 회원 토큰을 보내면 잘못된 회원 화면을 보게 될 수 있다.

시작 API의 `expiresAt`는 10분짜리 Google 요청 만료 시각이다. 인증 후 발급되는 가입 증명의 만료 시각과 구분한다. 동의 제출까지의 증명은 10분 고정이며 재조회·이메일 보완으로 연장되지 않는다.

명시적 연결은 기존 계정 로그인 상태에서 `link: true`, 현재 비밀번호를 시작 요청에 함께 제출한다. 대상 회원은 Bearer 토큰으로 결정한다. 클라이언트가 targetUserId나 임의 복귀 주소를 보내지 않는다. 기존 회원 연결에는 새 약관 동의가 없다.

## 모바일

GET `/progress`에서 받은 `callerBinding`을 가입 진행 동안 보관하고 이후 `X-Signup-Binding` 헤더로 보낸다. 인증 성공 응답의 `proof`는 `X-Signup-Proof` 헤더로 전달한다. 웹 CSRF 헤더와 쿠키는 사용하지 않는다. 기기·토큰 보관은 모바일의 기존 인증 저장 방식에 맞추되 URL이나 로그에 넣지 않는다.

POST `/api/mobile/auth/oauth/google/challenge`에 서버에 등록된 `registration`, `link: false`, `Device-Id`, 호출자 헤더를 보낸다. 응답의 `state`, `nonce`, `expiresAt` 중 nonce를 해당 Google 인증 요청에 결합한다. 얻은 ID 토큰과 state를 POST `/api/mobile/auth/oauth/google/complete`의 `idToken`, `state`로 제출한다. 등록 이름은 백엔드가 안내한 값을 사용하고 audience/clientId를 대신 보내지 않는다.

challenge 본문은 `{"registration": "<환경별 등록 이름>", "link": false}`이고 응답은 `{state: string, nonce: string, expiresAt: UTC 시각}`이다. `Device-Id`와 `X-Signup-Binding`은 본문 필드가 아니라 헤더다. 완료 본문은 `{"state": "<응답 state>", "idToken": "<Google ID 토큰>"}`이며 같은 `X-Signup-Binding`을 보낸다. Google access token이나 authorization code를 `idToken` 대신 보내지 않는다.

서버는 ID 토큰의 nonce가 challenge 응답의 nonce와 일치하는지 확인한다. 기존 Google 로그인에서 얻은 토큰을 그대로 재사용할 수 있다고 가정하지 않는다. 앱이 사용하는 인증 방식으로 해당 nonce가 포함된 ID 토큰을 만들 수 있는지 실제 기기에서 확인해야 한다. 이 모바일 API는 JSON으로 완료되며 서버가 앱 deep link로 303 이동시키지 않는다. 앱으로 돌아오는 동작은 앱의 Google 인증 등록에 맞게 처리한다.

모바일의 모든 가입 쓰기에는 `X-Signup-Binding`을 전달하고 소셜 이메일 코드 발송·이메일 보완·동의에는 `X-Signup-Proof`도 전달한다. 동의에는 `Device-Id`, 프로필 예약·완료·탈퇴에는 Bearer가 추가로 필요하다. 프로필 단계에서도 호출자 헤더를 생략하지 않는다.

회원이 결정된 응답은 `tokens`에 기존 모바일 형식인 `tokenType`, `accessToken`, `refreshToken`, `accessTokenExpiresIn`, `refreshTokenExpiresIn`을 담는다. 새 회원 인증 단계에서는 `tokens` 없이 `proof`와 `progress`를 받는다. 모바일 현재 회원 조회는 GET `/api/mobile/auth/me`다. 기존 로그인·재발급·로그아웃 API는 유지한다.

앱 재실행 시 진행 중인 `callerBinding`을 `X-Signup-Binding`으로 다시 보내고 보관한 `proof`도 전달해 GET `/api/mobile/auth/signup/progress`로 복구한다. 로그인 회원은 유효한 Bearer로 조회하고 액세스 토큰이 만료됐으면 POST `/api/mobile/auth/reissue`에 `{"refreshToken": "<저장한 갱신 토큰>"}`을 보내 새 `tokens`를 적용한다. 가입 인증 결과에 `tokens` 없이 `proof`만 있으면 이전 회원의 토큰을 해당 가입에 사용하지 않는다. 동의·프로필 응답 유실 시 상태 조회와 동일 입력 재시도를 사용하고, OAuth 완료 응답 유실은 소비된 state를 반복 제출하지 말고 Google 인증을 다시 시작한다.

진행 조회가 `AUTHENTICATE`이고 `userId`가 없으면 앱에 남은 `proof`를 삭제한다. 기존 이메일 로그인, `proof` 없는 기존 Google 회원 로그인, 프로필 완료·가입 중 탈퇴·로그아웃 성공 시에도 저장한 proof를 정리한다. `AUTHENTICATE`에 `userId`가 있으면 갱신 또는 기존 로그인으로 같은 회원의 세션을 복구한다.

명시적 연결은 인증된 Bearer 토큰과 `link: true`, 기존 비밀번호를 challenge 요청에 추가한다. 앱은 개발 중이므로 새 계약을 적용하며 구형 모바일 가입 계약을 장기 유지하지 않는다.

## 기존 계정의 Google 연결

최초 자동 연결과 명시적 연결은 `signup.enabled=true`일 때만 가능하다. 신규 가입 중지 시 최초 연결도 `SIGNUP_DISABLED`로 거절되며 기존 subject 연결의 로그인만 유지한다.

### 자동 연결과 이미 연결된 계정의 로그인

`link: false`로 일반 Google 로그인을 시작한다. 이미 같은 Google subject가 연결되어 있으면 제공자의 이메일 변경 여부와 관계없이 기존 userId로 로그인한다. 새 subject의 자동 연결은 다음 조건을 모두 만족해야 한다.

- Google 검증 결과의 이메일이 검증된 `@gmail.com`이며 서버에서 신뢰 가능한 이메일로 판정됨
- 기존 서비스 이메일과 대소문자를 제외하고 일치하며 `+tag`·점을 제거하지 않은 상태로 비교됨
- 기존 회원이 탈퇴하지 않았고 이메일 비밀번호 계정임
- 백엔드가 기존 이메일 가입의 인증 출처를 확인하고 `signup.legacy-email-accounts-verified`를 활성화함. 기본값은 false
- 해당 회원에 다른 Google subject가 이미 연결되어 있지 않음

Workspace 주소를 포함한 다른 도메인의 동일 이메일은 이 자동 연결에 포함되지 않는다. 동일한 기존 이메일을 찾았으나 자동 연결 조건이 안 맞으면 `ACCOUNT_LINK_CONFLICT`로 기존 로그인을 안내한다. Google 이메일을 서비스 이메일로 바로 신뢰할 수 없으면 신규 가입 증명의 `VERIFY_EMAIL` 단계에서 서비스 이메일을 인증한다. 이것만으로 이미 존재하는 다른 계정에 자동 연결되지는 않는다.

### 로그인 후 명시적 연결

1. 기존 이메일·비밀번호 로그인으로 대상 회원의 Bearer 토큰을 확보하고, 진행 조회로 웹 CSRF 또는 모바일 호출자를 준비한다.
2. 웹은 POST `/api/auth/oauth/google/start`에 `{"link": true, "password": "<기존 비밀번호>"}`를 전송한다. Bearer, `Device-Id`, 쿠키·Origin·`X-Signup-CSRF`가 필요하다.
3. 모바일은 POST `/api/mobile/auth/oauth/google/challenge`에 `{"registration": "<등록 이름>", "link": true, "password": "<기존 비밀번호>"}`를 전송한다. Bearer, `Device-Id`, `X-Signup-Binding`이 필요하다.
4. 시작 시 서버가 현재 비밀번호를 재검증하고 대상 userId를 OAuth 요청에 결합한다. 이후 완료는 일반 Google 흐름과 같다. 클라이언트는 `targetUserId`를 전송하지 않는다.
5. 완료 후 같은 기존 userId의 세션을 복구하고, 기존 회원이 `COMPLETED`이면 일반 서비스로 이동한다. `REQUIRED` 회원이면 프로필 설정을 이어간다. 기존 회원을 새로 생성하거나 약관 동의부터 다시 시작하지 않는다.

명시적 연결은 서비스 이메일과 Google 이메일이 달라도 허용하며 기존 서비스 이메일·닉네임·캐릭터를 덮어쓰지 않는다. Google subject가 다른 회원에 연결되어 있거나 대상 회원에 다른 Google subject가 이미 연결되어 있으면 409 `ACCOUNT_LINK_CONFLICT`다. 같은 회원·같은 subject의 연결은 기존 회원으로 처리한다.

현재 재인증 방식은 기존 비밀번호다. 비밀번호가 없는 소셜 전용 회원에게 이 연결 UI를 비밀번호 입력으로 진행시키지 않는다. 연결 목록 조회·연결 해제·다른 Google 계정으로 교체하는 별도 API는 이번 계약에 없다. 본인 조회 응답에도 연결 여부 필드가 없으므로 설정 화면에서 연결 상태를 지속적으로 표시하려면 별도 응답 보완이 필요하다.

## 실패와 재시도

JSON API는 `application/problem+json`의 `status`, `detail`, `type`, `instance`로 오류를 전달하며 가입·Google 처리기는 `code`를 추가한다. 공통 입력 검증·JSON 해석·보안 계층 오류에는 `code`가 없을 수 있으므로 `type`과 HTTP 상태로 처리한다. 입력 검증은 `fieldErrors`를 포함할 수 있다. 웹 콜백의 예상 가능한 인증 실패는 고정 프론트 주소에 `oauthError`만 붙인 303으로 돌아온다. `GOOGLE_CANCELLED`는 사용자의 취소이며 새 세션이 생겼다고 간주하지 않는다. 필수 query 누락·예상하지 못한 장애가 모두 303으로 복귀하는 것은 아니다.

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | `OAUTH_INVALID_REQUEST`, `OAUTH_NOT_FOUND`, `OAUTH_BINDING_MISMATCH`, `OAUTH_CHANNEL_MISMATCH` | 같은 호출자·채널로 새 Google 인증 시작 |
| 400 | `PROVIDER_NOT_SUPPORTED` | 지원 제공자 확인 |
| 401 | `GOOGLE_INVALID_IDENTITY` | 새 challenge·nonce로 재인증 |
| 401 | `INVALID_CREDENTIALS`, `USER_UNAVAILABLE` | 기존 계정 로그인·재인증과 회원 상태 확인 |
| 403 | `ORIGIN_FORBIDDEN`, `CSRF_INVALID` | Origin 설정·호출자 쿠키·CSRF 확인 |
| 409 | `ACCOUNT_LINK_CONFLICT`, `EMAIL_ALREADY_REGISTERED` | 기존 로그인 사용 후 허용된 명시적 연결 |
| 409 | `OAUTH_REPLAY` | 소비·처리 중 state 재사용 없이 재인증 |
| 410 | `OAUTH_EXPIRED` | 새 Google 인증 시작 |
| 429 | `OAUTH_RATE_LIMITED` | 잠시 후 새 인증 요청 |
| 503 | `OAUTH_DISABLED`, `GOOGLE_DISABLED` | Google 기능 사용 불가 안내 |
| 503 | `CONFIGURATION`, `OAUTH_CONFIGURATION`, `GOOGLE_CONFIGURATION` | 백엔드·Google 등록 설정 확인 |
| 503 | `GOOGLE_UNAVAILABLE` | 일시 실패 안내 후 새 인증 시작 |
| 503 | `SIGNUP_DISABLED` | 연결 없는 새 가입 중지 안내; 기존 subject 로그인과 구분 |

완료 요청의 장애·응답 유실은 계정 연결 취소를 뜻하지 않는다. 이미 연결이 커밋됐을 수 있으므로 기존 state·ID 토큰을 반복 제출하거나 연결을 자동 해제하지 않는다. 새 인증에서 같은 Google 계정을 검증하면 기존 회원으로 복구된다.

## 환경별 확정값

실제 API 주소·허용 Origin, Google에 등록한 전체 백엔드 redirect URI, query·fragment 없는 HTTPS 프론트 completion URI, 모바일 플랫폼·환경별 서버 registration 이름과 audience·authorized party 대응을 전달해야 한다. Google client secret·토큰·암호화 키를 클라이언트나 문서에 전달하지 않는다.

`Device-Id`는 비어 있지 않은 최대 128자 헤더다. 모바일 registration은 최대 64자이며 `web`을 사용할 수 없다. 모바일 state와 idToken은 각각 최대 128자·16,000자다. `link: true` 요청의 password는 기존 비밀번호이며 최대 72자다. `expiresAt`는 UTC ISO 8601 시각이다. 실제 기기 토큰의 nonce·audience·authorized party와 웹 HTTPS 쿠키·CORS는 환경별 확인이 필요하다.

일반 로그인·Google 완료·본인 조회는 회원 `id`, `nickname`, `avatarUrl`, `profileSetupStatus`, `characterId`를 전달한다. 연결 목록·해제·이메일 변경 API는 제공하지 않는다. 프론트·모바일의 파일 구성과 SDK 선택은 각 저장소에서 결정한다.
