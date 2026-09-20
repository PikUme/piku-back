# 챕터형 회원가입 변경 계약

- Status: 구현 계약, 배포·활성화 전
- Audience: 프론트엔드·모바일 개발자
- Source of Truth: Yes
- Last Reviewed: 2026-09-21

## 클라이언트가 바꿀 흐름

신규 가입은 **인증 → 필수 동의 → 닉네임·캐릭터 설정** 순서다. 인증 화면 제출은 아직 회원 생성이 아니다. 필수 동의를 제출하면 이메일 기반 기본 닉네임·기본 캐릭터를 가진 실제 회원과 로그인 세션이 생성된다. 이 회원은 `REQUIRED`이며 프로필 완료 후 `COMPLETED`가 된다.

서버가 반환하는 `progress.nextAction`을 기준으로 진행한다. 로그인했다는 사실만으로 서비스 초기화, 푸시 등록 또는 SSE 연결을 시작하지 않는다. `PROFILE`이면 프로필 설정을 먼저 완료한다. 기존 회원은 보통 `COMPLETE`, 중단한 신규 회원은 같은 userId의 `PROFILE`을 받는다.

| nextAction | 화면의 다음 동작 |
| --- | --- |
| `AUTHENTICATE` | 인증 시작. `userId`가 있으면 이미 생성된 회원이므로 기존 로그인으로 세션 복구 |
| `AGREEMENTS` | 최신 필수 약관 표시와 동의 제출 |
| `PROFILE` | 닉네임 점유 후 닉네임·고정 캐릭터 함께 제출 |
| `COMPLETE` | 일반 서비스 진입 |

`progress`에는 `nextAction`, `email`, `userId`, `profileSetupStatus`, `expiresAt`가 있다. 값이 없는 필드는 null일 수 있다. `expiresAt`는 회원 생성 전 가입 인증 증명의 만료 시각이며, 프로필 설정 기한이 아니다.

이 문서는 현재 백엔드 구현 계약이다. 운영 API 주소, 실제 약관 본문·버전은 환경별 확정값으로 별도 전달해야 한다. 캐릭터 ID는 동의·로그인·본인 조회의 `user.characterId`와 캐릭터 목록에서 읽는다. 아래의 설정값 예시는 운영 확정값으로 사용하지 않는다.

## API와 인증 전달

### 가입 API 전체 경로

아래 경로는 API 서버 기준 전체 경로다. 웹·모바일은 같은 가입 규칙을 사용하지만 호출 경로와 인증 전달 방식이 다르다.

| 기능 | 메서드 | 웹 엔드포인트 | 모바일 엔드포인트 |
| --- | --- | --- | --- |
| 진행 상태·호출자 초기화 | GET | `/api/auth/signup/progress` | `/api/mobile/auth/signup/progress` |
| 동의 문서 조회 | GET | `/api/auth/signup/agreements` | `/api/mobile/auth/signup/agreements` |
| 이메일 인증 코드 발송 | POST | `/api/auth/signup/email/code` | `/api/mobile/auth/signup/email/code` |
| 이메일 인증 화면 제출 | POST | `/api/auth/signup/email` | `/api/mobile/auth/signup/email` |
| 동의 제출·회원 생성 | POST | `/api/auth/signup/agreements` | `/api/mobile/auth/signup/agreements` |
| 닉네임 중복 확인·예약 | POST | `/api/auth/signup/nickname` | `/api/mobile/auth/signup/nickname` |
| 닉네임·캐릭터 확정 | POST | `/api/auth/signup/profile` | `/api/mobile/auth/signup/profile` |
| 가입 미완료 회원 탈퇴 | DELETE | `/api/auth/signup/profile` | `/api/mobile/auth/signup/profile` |

### 세션 복구와 캐릭터 API

| 기능 | 메서드 | 웹 엔드포인트 | 모바일 엔드포인트 | 요청·응답 |
| --- | --- | --- | --- | --- |
| 기존 이메일 로그인 | POST | `/api/auth/login` | `/api/mobile/auth/login` | JSON `email`, `password`와 `Device-Id`. 웹은 `{message, user}`와 토큰 헤더·쿠키, 모바일은 `{message, user, tokens}` |
| 토큰 재발급 | POST | `/api/auth/reissue` | `/api/mobile/auth/reissue` | 웹은 본문 없이 `rn` 쿠키, 응답 `{message}`와 `Authorization` 헤더. 모바일은 JSON `refreshToken`, 응답 `{message, tokens}` |
| 본인 조회 | GET | `/api/auth/me` | `/api/mobile/auth/me` | Bearer 토큰, 응답 `{message, user}` |
| 고정 캐릭터 목록 | GET | `/api/characters/fixed` | `/api/characters/fixed` | 인증 없이 조회 가능. `{id, displayImageUrl, type}` 객체의 배열. `type`은 `FIXED` |

모바일도 캐릭터 조회에는 공통 경로를 사용한다. `/api/mobile/characters/fixed`는 제공하지 않는다.

### 가입 요청·응답

이하 요청·응답 표에서만 하위 경로로 축약한다. 기준 경로는 웹 `/api/auth/signup`, 모바일 `/api/mobile/auth/signup`이다. 본문은 JSON이다. 모바일은 웹 쿠키를 인증 증명으로 사용하지 않는다.

| 메서드·하위 경로 | 요청 | 응답과 의미 |
| --- | --- | --- |
| GET `/progress` | 로그인했다면 Bearer 토큰. 진행 중 증명은 아래 전달 규칙 적용 | `enabled`, `progress`, 웹 `csrfToken` 또는 모바일 `callerBinding`. 페이지 진입으로 DB 가입 레코드를 생성하지 않음 |
| GET `/agreements` | 없음 | 문서 배열: `type`, `version`, `content`, `required` |
| POST `/email/code` | `email`, 선택 `challengeId`, 선택 `restartAuthentication` | `challengeId`, `expiresAt`, `resendAvailableAt`. 일반 이메일 가입 코드만 발송하며 소셜 증명에는 결속하지 않음. true면 이전 challenge와 분리된 새 인증 시작 |
| POST `/email` | `challengeId`, `email`, `code`, `password` | 이메일 인증·비밀번호 검증 후 `progress`와 가입 증명 전달 |
| POST `/agreements` | `agreements` 배열의 `type`, `version`, `agreed`; 가입 증명과 `Device-Id` 필수 | 회원 생성 후 `progress`, `user`, 로그인 자격 전달 |
| POST `/nickname` | Bearer 토큰, `nickname` | `nickname`, `expiresAt`. 3분 예약 |
| POST `/profile` | Bearer 토큰, `nickname`, `characterId` | `userId`, `nickname`, `characterId`, `profileSetupStatus: COMPLETED` |
| DELETE `/profile` | Bearer 토큰 | 가입 미완료 회원 탈퇴, 204. 완료 회원 탈퇴 API가 아님 |

닉네임은 앞뒤 공백을 정리한 뒤 1~20자여야 하며, 정리한 값이 `가입대기_`로 시작하면 선택할 수 없다. 일반 공백·탭·줄바꿈·전각 공백 등 서버의 공백 정리 대상에 해당하는 문자를 제거하며 내부 공백은 유지한다. 중복 확인 응답의 정규화된 닉네임을 화면에 반영하고 완료 요청에 사용한다. 같은 닉네임의 중복 확인은 기존 예약 시간을 연장하지 않는다. 수정한 닉네임의 예약이 만료되면 중복 확인을 다시 수행한다. 새 닉네임 예약 실패 시 이전 예약은 유지된다. 캐릭터는 기존 GET `/api/characters/fixed`의 실제 ID를 사용한다. 기본 캐릭터를 그대로 선택해도 완료할 수 있다.

검증된 이메일의 local-part를 기본 닉네임으로 사용한다. `haru@example.com`은 `haru`가 되며 이메일 도메인은 포함하지 않는다. 점·`+tag`·대소문자는 보존하고 20자를 넘으면 앞부분을 사용한다. 기존 회원 또는 다른 회원의 유효 예약과 충돌하면 local-part를 최대 16자로 줄이고 1000~9999의 4자리 숫자를 붙인다. 빈 local-part나 `가입대기_` 접두어처럼 최종값으로 사용할 수 없는 경우에는 `사용자`를 기본 후보로 삼는다. 이메일 자체의 유효성 검증과 계정 식별 규칙은 변경하지 않는다.

프로필 입력란은 서버 응답의 `user.nickname`으로 미리 채운다. 정규화 후 본인에게 저장된 현재 기본 닉네임과 같으면 별도 중복 확인·예약 없이 캐릭터와 함께 완료할 수 있다. 수정한 닉네임에는 기존 3분 예약이 필요하다. 기본값으로 되돌려 완료하면 그 회원의 다른 예약도 같은 트랜잭션에서 해제한다. 본인 현재값으로 중복 확인 API를 호출해도 기존처럼 `nickname`, 유효한 `expiresAt`을 반환하며 null 만료 시각을 추가하지 않는다. 기존 `가입대기_` 닉네임을 쓰는 미완료 회원은 새 닉네임을 예약·선택해야 한다. 완료 API 제출 전에는 기본값을 가지고 있어도 `REQUIRED`와 이용 제한을 유지한다.

인증 화면으로 돌아가 다른 이메일로 가입하거나 소셜 가입을 이메일 가입으로 바꾸면 코드 발송에 restartAuthentication: true를 보낸다. 서버는 이전 challengeId를 사용하지 않으며 발송 성공 후 웹의 이전 증명 쿠키를 지운다. 모바일은 성공 후 저장한 proof를 지우고 새 challengeId를 사용한다. 실패 시 이전 증명을 유지한다. 일반 재발송은 새 challengeId와 기본값 false를 사용한다. 코드 발송은 항상 일반 이메일 가입이며 기존 소셜 증명의 이메일을 수정하지 않는다. 옵션으로 기존 회원이나 가입 자료를 삭제하지 않으며 발송 제한을 우회하지 않는다.

### 필드 형식과 공통 응답

별도 표기한 204를 제외한 위 API의 성공 상태는 200이다. POST 본문은 `Content-Type: application/json`으로 전송한다. 서비스 회원 ID·서비스 토큰·이메일을 query에 넣지 않는다. `challengeId`와 `userId`는 문자열, `characterId`와 캐릭터 목록의 `id`는 양의 정수다. `expiresAt`, `resendAvailableAt`은 UTC ISO 8601 시각이며 클라이언트에서 화면용 남은 시간을 계산한다.

| 필드 | 형식·제약 |
| --- | --- |
| `email` | 이메일 문자열. 코드 발송 요청은 최대 255자. Gmail의 점이나 `+tag`를 제거하지 않음 |
| `challengeId` | 서버 발송 응답의 문자열. 재발송 시 직전 값을 전달하며 인증 화면 제출에서는 필수 |
| `restartAuthentication` | 선택 boolean, 기본 false. true는 이전 challenge를 재사용하지 않고 별도의 일반 이메일 가입 인증 시작 |
| `code` | 숫자 6자리 문자열. 앞자리 0을 유지 |
| 이메일 가입 `password` | 길이 1~72자. 영문·숫자·`!@#$%^&*`만 허용하며 해당 특수문자 최소 1개 포함 |
| `agreements` | 1~20개 객체 배열. 각 객체는 `type: string`, `version: string`, `agreed: boolean`. 동일 `type` 중복 제출 불가 |
| `nickname` | 필수. 앞뒤 공백 정리 후 1~20자. 예약·완료 응답은 정규화된 값이며 앞뒤 공백만 다른 완료 재시도는 같은 값으로 처리 |
| `characterId` | 고정 캐릭터 목록에서 선택한 양의 정수 |
| `Device-Id` | 비어 있지 않은 최대 128자 헤더. 동의 제출에서 필수 |

진행 조회의 최상위 필드는 `enabled: boolean`, `progress: object`와 웹의 `csrfToken: string` 또는 모바일의 `callerBinding: string`이다. `enabled`는 신규 가입 기능 상태이며 외부 인증 기능의 활성 여부를 나타내는 필드가 아니다.

이메일 인증·동의 제출은 아래의 공통 단계 응답을 사용한다. 최상위의 값 없는 `user`, `tokens`, `proof`는 JSON에서 생략된다.

| 응답 필드 | 형식·등장 조건 |
| --- | --- |
| `progress.nextAction` | `AUTHENTICATE`, `AGREEMENTS`, `PROFILE`, `COMPLETE` 중 하나. 이메일 코드 발송 전후 UI 상태는 클라이언트가 challenge 응답으로 관리 |
| `progress.email` | 문자열 또는 null. 인증된 회원의 진행 조회에서는 null일 수 있으므로 회원 이메일 조회 용도로 사용하지 않음 |
| `progress.userId` | 문자열 또는 null. 회원이 정해지기 전에는 null |
| `progress.profileSetupStatus` | `REQUIRED`, `COMPLETED` 또는 null |
| `progress.expiresAt` | 가입 증명 만료 시각 또는 null. 회원 생성 이후에도 증명에서 이어진 응답이면 시각이 남을 수 있음 |
| `user` | 회원이 결정되면 `{id: string, nickname: string, avatarUrl: string 또는 null, profileSetupStatus: string, characterId: number 또는 null}`. `characterId`는 현재 회원에 저장된 선택이며 신규 회원은 기본 캐릭터 ID. 이메일과 외부 계정 연결 여부는 포함하지 않음 |
| `tokens` | 모바일에서 회원이 결정됐을 때만 포함. `tokenType: "Bearer"`, `accessToken: string`, `refreshToken: string`, `accessTokenExpiresIn: number`, `refreshTokenExpiresIn: number`. 만료 기간 단위는 초 |
| `proof` | 모바일에서 가입 증명이 생성되거나 전달될 때 문자열로 포함. 동의 후 회원 생성 응답에도 남을 수 있음. 웹은 본문에 포함하지 않음 |

회원 생성 전 이메일 인증 성공의 웹 응답 예시다. 시각과 이메일은 설명용 값이며 가입 증명은 응답 쿠키로 전달된다.

```json
{
  "progress": {
    "nextAction": "AGREEMENTS",
    "email": "example@gmail.com",
    "userId": null,
    "profileSetupStatus": null,
    "expiresAt": "2026-09-08T10:10:00Z"
  }
}
```

같은 단계의 모바일 응답은 최상위 `proof`를 추가한다. 동의로 신규 회원을 처음 생성하면 `progress.nextAction: PROFILE`, `user.profileSetupStatus: REQUIRED`와 이메일 기반 기본 닉네임·기본 캐릭터의 `characterId`·이미지 URL을 반환하며 모바일에서는 `tokens`도 추가한다. 재시도나 기존 연결 회원으로 복구된 응답에서는 실제 `progress`를 따른다. 프로필 완료 API의 응답은 이 단계 응답과 달리 최상위 `{userId, nickname, characterId, profileSetupStatus}`이며 새 토큰을 발급하지 않는다.

### 동의 문서와 기본 캐릭터

GET `/api/auth/signup/agreements` 또는 `/api/mobile/auth/signup/agreements`는 포장 객체 없이 문서 배열을 반환한다. `type`은 서버가 정한 문서 식별 문자열이고 `version`은 불투명한 버전 문자열이며, 클라이언트가 `TERMS`, 날짜 형식 등으로 고정하지 않는다. `content`는 실제 표시할 본문 문자열이고 `required`가 true인 모든 문서에 동의해야 한다. 현재 응답에는 별도 `title`, `contentUrl`, `contentFormat`, `effectiveAt` 필드가 없다. HTML 형식으로 가정해 실행하지 않는다.

동의 화면을 표시할 때 최신 목록을 조회하고 해당 `content`를 보여준다. 제출 시 조회한 `type`·`version`을 그대로 돌려보내며 `content`는 전송하지 않는다. 선택 문서는 생략하거나 `agreed: false`로 제출할 수 있다. 제출한 선택 문서에도 현재 버전 검증은 적용된다. 버전 불일치의 409에서는 목록을 다시 조회해 바뀐 본문을 확인하게 하고 다시 동의를 받는다. 기존 동의를 최신 버전에 자동 적용하지 않는다.

아래는 요청 구조만 나타낸 예시이며 꺾쇠 안의 문자열은 GET 응답값으로 교체한다.

```json
{
  "agreements": [
    {"type": "<조회한 문서 type>", "version": "<조회한 version>", "agreed": true}
  ]
}
```

약관의 실제 `type`, `version`, `content`, `required`는 서버의 `signup.agreements` 설정에서 제공한다. 저장소에 운영 확정 본문·버전이 들어 있지 않으며 기본 목록은 빈 배열이다. 가입을 켜려면 본문과 버전이 있는 필수 문서가 먼저 설정되어야 한다. 빈 배열을 받으면 동의 없이 가입을 진행하지 않는다.

기본 캐릭터는 서버가 `base_image_1.webp`에 대응하는 공용 고정 캐릭터를 정확히 하나 찾았을 때 그 DB ID로 저장한다. 파일명의 `1`은 캐릭터 ID `1`을 의미하지 않는다. 대상이 없거나 중복이면 회원 생성 요청이 503 `DEFAULT_CHARACTER_UNAVAILABLE`로 실패한다.

| 필요한 값 | 현재 제공 여부 | 클라이언트 적용 |
| --- | --- | --- |
| 선택 가능한 캐릭터 ID와 이미지 | GET `/api/characters/fixed`의 `id`, `displayImageUrl`, `type` | 사용자가 고른 실제 `id`를 프로필 완료에 제출 |
| 가입 직후 기본 캐릭터 이미지 | 동의 응답의 `user.avatarUrl` | 가입 프로필 화면의 초기 이미지 표시 가능 |
| 가입 직후 기본 캐릭터 ID와 현재 선택 | 동의·로그인·웹 및 모바일 본인 조회의 `user.characterId` | 목록의 `id`와 비교해 선택 복구. 변경 없이 완료할 때도 이 ID를 제출 |

새로고침 후 세션을 복구하고 본인 조회의 `user.characterId`로 선택을 복원한다. 이미지 URL·목록 순서·파일명에서 ID를 추론하거나 숫자를 하드코딩하지 않는다. 기존 데이터에서 ID가 null이거나 현재 고정 목록에 없으면 목록에서 사용자가 선택하게 한다. 신규 회원의 기본 ID 누락을 클라이언트 임의 값으로 보충하지 않는다.

## 웹

GET `/progress`로 받은 `csrfToken`을 이후 신규 가입 쓰기의 `X-Signup-CSRF` 헤더로 전송한다. 허용된 `Origin`과 쿠키가 함께 필요하다. 쿠키는 host-only, Secure, HttpOnly, SameSite=Lax다. 브라우저 요청은 쿠키를 포함하도록 구성한다. 개발 환경도 이 쿠키 계약에 맞는 HTTPS를 준비한다.

프론트 요청에는 `credentials: include`에 해당하는 옵션을 적용한다. CSRF 값은 HttpOnly 쿠키를 읽는 대신 진행 조회 JSON에서 얻어 메모리에 보관한다. 새로고침 후에는 같은 GET으로 복구한다. 회원가입 프로필 예약·완료·가입 중 탈퇴도 CSRF 적용 대상이며, Bearer 토큰이 있어도 이 검사를 생략하지 않는다. 기존 로그인·재발급·본인 조회에는 신규 `X-Signup-CSRF` 검사를 추가하지 않았다.

| 요청 | 필요한 자격 |
| --- | --- |
| GET `/api/auth/signup/progress` | 쿠키 포함. 로그인 상태 조회 시 Bearer도 전송. `Origin`이 있으면 허용 목록 검사 |
| GET `/api/auth/signup/agreements`, `/api/characters/fixed` | 로그인·가입 증명 불필요 |
| POST `/email/code`, `/email` | 허용 Origin + 호출자·CSRF 쿠키 + `X-Signup-CSRF`. 일반 이메일 코드 발송·인증에는 증명 쿠키를 사용하지 않음 |
| POST `/agreements` | 위 자격 + 가입 증명 쿠키와 `Device-Id` |
| POST `/nickname`, POST·DELETE `/profile` | 허용 Origin + 호출자·CSRF 쿠키 + `X-Signup-CSRF` + Bearer |

이 표의 가입 하위 경로 기준은 `/api/auth/signup`이다. 호출자 쿠키는 `__Host-pk-signup-binding`, CSRF 쿠키는 `__Host-pk-signup-csrf`이며 각각 최대 24시간이다. 가입 증명 쿠키의 Max-Age는 DB 증명의 실제 남은 초로 설정한다. 재응답으로 10분이 다시 시작되지 않으며 이미 만료됐으면 삭제한다. 최종 유효성은 서버가 판단한다. CORS는 `Authorization` 응답 헤더를 프론트가 읽도록 노출한다.

현재 허용 Origin은 `https://pikume.com`, `https://www.pikume.com`, `http://localhost:3000`, `http://localhost:3001`이다. HTTPS 로컬 주소나 별도 스테이징 주소는 자동 허용되지 않는다. 개발·스테이징에서는 실제 사용할 Origin의 허용과 Secure·SameSite 쿠키 전달을 함께 확인해야 하며, HTTPS만 준비했다고 호출 가능한 것으로 간주하지 않는다.

웹 가입 증명은 `__Host-pk-signup-proof` 쿠키로 전달되며 응답 본문에 노출하지 않는다. 호출자 결합·CSRF 쿠키도 서버가 관리한다. 증명·세션 토큰을 URL, 로컬 저장소 또는 로그에 복사하지 않는다.

회원 생성 또는 기존 회원 로그인 성공 시 액세스 토큰은 `Authorization: Bearer …` 응답 헤더, 갱신 토큰은 기존 `rn` HttpOnly 쿠키로 전달한다. `user`는 기존 `id`, `nickname`, `avatarUrl`에 `profileSetupStatus`, `characterId`가 추가된다. 일반 로그인과 GET `/api/auth/me`도 이 값을 반환한다.

이메일 로그인 성공과 웹 로그아웃 성공 시 이전 가입 증명 쿠키를 정리한다. 호출자·CSRF 쿠키는 유지한다. 동의 후 회원 생성 응답에서는 동일 요청 복구에 필요한 증명을 원래 만료 시각까지 유지한다.

## 모바일

GET `/progress`에서 받은 `callerBinding`을 가입 진행 동안 보관하고 이후 `X-Signup-Binding` 헤더로 보낸다. 인증 성공 응답의 `proof`는 `X-Signup-Proof` 헤더로 전달한다. 웹 CSRF 헤더와 쿠키는 사용하지 않는다. 기기·토큰 보관은 모바일의 기존 인증 저장 방식에 맞추되 URL이나 로그에 넣지 않는다.

모바일의 모든 가입 쓰기에는 `X-Signup-Binding`을 전달하고 동의에는 `X-Signup-Proof`도 전달한다. 일반 이메일 발송·인증은 소셜 증명에 결속하지 않는다. 동의에는 `Device-Id`, 프로필 예약·완료·탈퇴에는 Bearer가 추가로 필요하다. 프로필 단계에서도 호출자 헤더를 생략하지 않는다.

회원이 결정된 응답은 `tokens`에 기존 모바일 형식인 `tokenType`, `accessToken`, `refreshToken`, `accessTokenExpiresIn`, `refreshTokenExpiresIn`을 담는다. 새 회원 인증 단계에서는 `tokens` 없이 `proof`와 `progress`를 받는다. 모바일 현재 회원 조회는 GET `/api/mobile/auth/me`다. 기존 로그인·재발급·로그아웃 API는 유지한다.

앱 재실행 시 진행 중인 `callerBinding`을 `X-Signup-Binding`으로 다시 보내고 보관한 `proof`도 전달해 GET `/api/mobile/auth/signup/progress`로 복구한다. 로그인 회원은 유효한 Bearer로 조회하고 액세스 토큰이 만료됐으면 POST `/api/mobile/auth/reissue`에 `{"refreshToken": "<저장한 갱신 토큰>"}`을 보내 새 `tokens`를 적용한다. 가입 인증 결과에 `tokens` 없이 `proof`만 있으면 이전 회원의 토큰을 해당 가입에 사용하지 않는다. 동의·프로필 응답 유실 시 상태 조회와 동일 입력 재시도를 사용한다.

진행 조회가 `AUTHENTICATE`이고 `userId`가 없으면 앱에 남은 `proof`를 삭제한다. 기존 이메일 로그인, 프로필 완료·가입 중 탈퇴·로그아웃 성공 시에도 저장한 proof를 정리한다. `AUTHENTICATE`에 `userId`가 있으면 아래 세션 유실 복구를 따른다.

## 실패와 재시도

신규 가입의 닉네임 예약·프로필 완료 API는 닉네임 누락·빈 값·정규화 후 길이 초과·서버 전용 접두어를 400 `INVALID_NICKNAME`으로 거절한다. 기존 회원의 일반 프로필·닉네임 API가 사용하는 공통 검증 Problem Details와 구분한다.

진행 조회는 화면 복구 API다. 로그인 토큰이 없고 가입 증명이 만료·무효이거나 구 가입 흐름의 증명, 탈퇴·삭제된 회원의 잔존 증명이면, 웹은 남은 증명 쿠키를 지우고 200 `AUTHENTICATE`와 `csrfToken`을 반환한다. 모바일은 같은 상태와 `callerBinding`을 반환하며 쿠키를 설정하지 않는다. 증명을 요구하는 쓰기는 계속 400 또는 410 Problem Details로 거절하므로, 오류 후 진행 조회를 거쳐 재인증한다. DB 장애 등 예상하지 못한 서버 오류를 인증 초기화 성공으로 바꾸지 않는다.

동의로 회원을 생성했지만 로그인 세션을 잃었다면 먼저 갱신 토큰으로 재발급을 시도한다. 복구되지 않아 Bearer 없이 진행을 조회하면, 유효한 소비 증명에 대해 `AUTHENTICATE`와 기존 `userId`·`profileSetupStatus`를 반환한다. 이때는 가입 코드 인증부터 반복하지 말고 기존 이메일·비밀번호 로그인으로 같은 회원의 세션을 복구한다. 진행 조회 자체는 세션을 발급하지 않는다. 원래 동의 내용과 유효 증명을 보관했다면 동일 동의 제출 재시도도 가능하다. 이미 인증된 회원의 진행 조회는 증명보다 현재 회원 상태를 우선해 `PROFILE` 또는 `COMPLETE`를 반환한다.

JSON API 오류는 RFC 9457 `application/problem+json`이며 `type`, `title`, `status`, `detail`, `instance`를 확인한다. 신규 가입의 비즈니스 오류에는 `code`가 추가된다. 요청 형식·필드 검증 오류는 기존 공통 검증 응답을 사용하므로 `code`가 없을 수 있다. 회원 기능의 접근 제한은 `type`이 `https://api.pikume.com/problems/signup/profile-setup-required`인 403으로 구분한다. 이 응답은 보안 필터에서 만들어질 수도 있으므로 `code`나 `nextAction`이 항상 존재한다고 가정하지 않는다.

가입 오류의 `type`은 `https://api.pikume.com/problems/signup/` 다음에 `code`를 소문자로 바꾸고 밑줄을 하이픈으로 바꾼 값이다. 예를 들어 `HOLD_REQUIRED`는 `https://api.pikume.com/problems/signup/hold-required`다. 사용자 안내 문구인 `detail`을 문자열 비교해 화면을 분기하지 않는다.

```json
{
  "type": "https://api.pikume.com/problems/signup/proof-expired",
  "title": "Gone",
  "status": 410,
  "detail": "가입 인증이 만료되었습니다. 다시 인증해주세요.",
  "instance": "/api/auth/signup/agreements",
  "code": "PROOF_EXPIRED",
  "nextAction": "AUTHENTICATE"
}
```

아래는 신규 가입 JSON API가 사용하는 오류 코드와 HTTP 상태다. 묶은 코드는 같은 상태·복구 방향을 사용한다.

| HTTP | `code` | 클라이언트 처리 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_PASSWORD` | 입력 수정. 허용 이메일·비밀번호 정책 확인 |
| 400 | `PROOF_INVALID`, `FLOW_MISMATCH` | 현재 진행을 조회하고 올바른 인증 흐름으로 다시 시작 |
| 400 | `CHALLENGE_INVALID` | 현재 일반 이메일 인증 시도와 호출자의 결합 확인 후 코드 재발송 |
| 400 | `CODE_MISMATCH` | 코드 재입력. 같은 오입력을 자동 재시도하지 않음 |
| 400 | `EMAIL_REQUIRED`, `INVALID_EMAIL` | 이메일 누락·부적합으로 증명·회원 생성 없이 `AUTHENTICATE`로 재시작. 소셜 가입은 제공자 이메일을 직접 수정할 수 없고, 일반 이메일 가입은 입력을 수정 |
| 400 | `AGREEMENTS_REQUIRED` | 필수 문서의 동의 여부 확인 |
| 400 | `INVALID_NICKNAME`, `INVALID_CHARACTER` | 닉네임 수정 또는 고정 캐릭터 목록 재조회 |
| 400 | `CALLER_REQUIRED` | 진행 조회로 호출자 쿠키·헤더 초기화. 호출자를 잃었으면 종전 증명을 재사용할 수 있다고 가정하지 않음 |
| 400 | `PROOF_REQUIRED` | 가입 증명 전달 확인. 증명이 없으면 인증 재시작 |
| 400 | `DEVICE_REQUIRED` | 유효한 `Device-Id` 헤더를 추가 |
| 401 | `USER_UNAVAILABLE`, `INVALID_CREDENTIALS` | 기존 로그인·재인증 확인. 사용할 수 없는 회원으로 가입을 이어가지 않음 |
| 403 | `ORIGIN_FORBIDDEN` | 실제 Origin 허용 설정 확인. 재시도만으로 해결되지 않음 |
| 403 | `CSRF_INVALID` | 진행 조회에서 CSRF를 다시 받고 쿠키와 함께 재요청 |
| 403 | `PROFILE_SETUP_REQUIRED` | 본인 진행 조회 후 가입 프로필 화면으로 이동 |
| 409 | `PROOF_ALREADY_USED` | 상태 조회 후 이미 만들어진 회원 복구. 다른 제출 내용으로 증명을 재사용하지 않음 |
| 409 | `AGREEMENT_VERSION_MISMATCH` | 최신 본문을 다시 표시하고 동의 받기 |
| 409 | `ACCOUNT_LINK_CONFLICT`, `EMAIL_ALREADY_REGISTERED` | 기존 로그인으로 같은 회원 복구. 외부 계정 연결은 제공자 인증 변경의 범위 |
| 409 | `NICKNAME_COLLISION` | 기본 닉네임 후보 소진 또는 생성 충돌. 같은 동의 요청으로 재시도 가능 |
| 409 | `HOLD_REQUIRED` | 닉네임 중복 확인을 다시 수행 |
| 409 | `NICKNAME_UNAVAILABLE` | 다른 닉네임 선택. 이전 예약은 유지 |
| 409 | `PROFILE_ALREADY_COMPLETED` | 현재 상태 조회 후 일반 서비스 또는 일반 프로필 변경 흐름 |
| 410 | `PROOF_EXPIRED` | 회원 생성 전 인증을 다시 시작. 회원 생성 후라면 기존 로그인으로 재개 |
| 410 | `CODE_EXPIRED` | 유효한 가입 증명 범위 안에서 코드 재발송 |
| 410 | `LEGACY_SIGNUP_DISABLED` | 새 단계별 가입 경로 사용 |
| 429 | `RATE_LIMITED`, `ATTEMPTS_EXHAUSTED` | 잠시 후 재요청. 발송 응답의 `resendAvailableAt` 준수. 표준 `Retry-After` 헤더나 남은 시도 횟수 응답은 현재 보장하지 않음 |
| 503 | `SIGNUP_DISABLED` | 신규 가입 일시 중지 안내. |
| 503 | `DEFAULT_CHARACTER_UNAVAILABLE` | 기본 캐릭터 준비 필요. 클라이언트가 임의 ID로 우회하지 않음 |
| 503 | `EMAIL_SEND_FAILED` | 발송 실패 안내 후 재시도 |
| 503 | `CONFIGURATION` | 백엔드 설정 확인 필요 |

공통 검증 400은 `https://api.pikume.com/problems/validation/invalid-request`, JSON 해석 실패 등은 `https://api.pikume.com/problems/common/malformed-request`를 사용한다. 세션 API의 401은 `https://api.pikume.com/problems/security/unauthenticated`, `https://api.pikume.com/problems/security/invalid-credentials`, `https://api.pikume.com/problems/security/invalid-refresh-token` 등의 `type`으로 구분하고 `code`가 없을 수 있다. 알 수 없는 오류는 HTTP 상태에 맞는 안내와 재시도를 제공한다.

동의 응답 유실 시 동일한 증명과 같은 동의 내용으로 재시도할 수 있다. 프로필 완료 응답 유실 시 같은 닉네임·캐릭터로 재시도할 수 있다. 회원 생성 후에는 증명 만료와 관계없이 다시 로그인해 프로필을 마칠 수 있다.

인증·동의·코드 재발송을 만료 전에 요청했더라도 서버 잠금 대기 중 만료되면 만료 오류를 받을 수 있다. 코드 확인이 먼저 완료되면 같은 challenge의 재발송은 `CHALLENGE_INVALID`로 거절된다. 재발송이 먼저 시작되면 발송 중에는 코드 확인이 `CHALLENGE_INVALID`로 거절되고, 완료 후에는 최신 코드와 다른 값이 `CODE_MISMATCH`로 거절되므로 발송 응답을 받은 뒤 최신 코드로 진행한다.

## 기존 웹 전환

전환 기간의 POST `/api/auth/signup`은 실제 이메일 인증 성공 시 발급한 가입 증명 쿠키와 같은 브라우저 결합을 요구한다. 이메일 문자열만으로 인증 사실을 가져오지 않는다. 기존 성공 본문은 유지하며 허용 Origin의 JSON 요청과 쿠키 포함이 필요하다. 신규 단계별 가입이 활성화되면 구형 가입·가입 코드 발송·가입 목적 코드 확인은 410 `LEGACY_SIGNUP_DISABLED`로 닫힌다. 비밀번호 재설정 흐름은 별도다.

## 환경별로 전달할 확정값과 계약 보완

| 항목 | 현재 확인 상태 | 필요한 전달 내용 |
| --- | --- | --- |
| 개발·스테이징·운영 API 주소 | 이 문서의 경로는 호스트를 제외한 전체 API 경로 | 각 환경의 API base URL과 허용 프론트 Origin |
| 필수 동의 문서 | 운영 확정 본문·버전은 저장소에 없음 | 문서별 `type`, `version`, 실제 `content`, `required`. 설정 후 실제 GET 응답으로 확인 |
| 기본 캐릭터 ID | 환경별 DB 값. 동의·로그인·본인 조회의 `user.characterId`로 제공 | 환경별 기본 카탈로그 준비 후 응답 ID와 캐릭터 목록의 일치 확인 |
| 기존 Gmail 자동 연결 | 기본 비활성화 | 기존 이메일 가입의 인증 출처 확인 후 활성화 여부 |

API·약관 설정과 기본 캐릭터가 준비되기 전까지 실제 서비스 값이 확정된 것으로 전달하지 않는다. 토큰과 서버 비밀값은 문서나 로그의 전달 대상이 아니다.

이 문서는 백엔드가 제공하는 화면 흐름과 API 계약만 다룬다. 프론트·모바일의 파일 구성, 상태 관리, SDK 선택과 구현 순서는 각 저장소에서 결정한다.

## 외부 로그인과의 경계

신규 소셜 증명은 검증된 제공자 결과의 유효 이메일을 필수로 포함하고 바로 `AGREEMENTS`로 진행한다. 소셜 이메일 보완 API와 `VERIFY_EMAIL` 상태는 제공하지 않는다. 이 변경만으로 외부 인증이나 연결 시작 API를 호출할 수는 없다. 이메일 가입은 별도 제공자 인증 없이 인증·동의·프로필의 모든 단계를 완료할 수 있다.
