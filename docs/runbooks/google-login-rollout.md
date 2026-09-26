# Google 로그인 전환 운영

- Status: 활성화 전 운영 기준
- Audience: 백엔드·운영 담당자
- Source of Truth: Yes
- Last Reviewed: 2026-09-21

## 선행 조건과 활성화

챕터형 가입의 V16–V18·동의·프로필·공통 세션 기능이 먼저 반영되어야 한다. Google PR은 V19에서 소셜 계정 연결·가입 증명 메타데이터·OAuth 요청 저장소를 추가하고 Google 프로토콜을 제공한다. 코드 배포만으로 기능을 열지 않으며 `signup.google.enabled`는 기본 false다. 신규 가입의 `signup.enabled`와 별도 스위치다. 실제 환경 연동 확인과 활성화는 PR 분리 작업에 포함하지 않는다.

최초 Gmail 자동 연결과 로그인 후 명시적 연결도 `signup.enabled=true`를 요구한다. 신규 가입을 닫으면 최초 연결도 중지되며 이미 저장된 subject 연결 로그인만 유지한다. Google 기능만 켜서 미연결 회원의 최초 연결을 제공할 수는 없다.

## 필요한 설정

| 설정 | 기본값 또는 요구 조건 |
| --- | --- |
| `signup.legacy-email-accounts-verified` | false. 기존 비밀번호 회원의 Gmail 자동 연결을 허용하기 전 실제 가입 경로·이관 계정 출처 확인 |
| `signup.google.enabled` | false. 이미 연결된 Google 회원 로그인까지 포함하는 인증 기능 스위치 |
| `signup.google.web-client-id` / `web-client-secret` / `web-redirect-uri` | 실제 Google 웹 등록과 정확히 일치. 콜백은 `/api/auth/oauth/google/callback` |
| `signup.google.mobile-registrations` | 서버 등록 이름별 audience, authorizedParty. 앱이 제출하는 이름을 고정하고 실제 Google 토큰 발급 구조에 맞춰 설정 |
| `signup.protocol.encryption-key` | Google 활성화 시 필요한 Base64 인코딩 32바이트 키. 관리형 비밀 저장소에서 주입 |
| `signup.protocol.caller-hourly-limit` / `origin-hourly-limit` | 20 / 100 |
| `signup.protocol.cleanup-batch-size` / `cleanup-interval-ms` | 500 / 300,000 |
| `signup.web.completion-uri` | Google 인증 후 돌아갈 신뢰하는 프론트 HTTPS 주소. query·fragment·userinfo 없는 고정 주소 |

실제 비밀 키·클라이언트 secret·토큰을 문서나 로그에 넣지 않는다. 모든 인스턴스가 동일한 암호화 키를 사용해야 한다. 이 키는 DB의 OAuth nonce·PKCE verifier를 암호화하므로 무계획한 교체는 진행 중 로그인을 복구 불가능하게 만든다. 교체 시 새 인증 시작을 잠시 닫고 기존 10분 요청 만료를 기다린 후 모든 인스턴스를 같은 키로 전환한다.

모바일 등록의 audience와 authorizedParty를 서로 같은 값이라고 가정하지 않는다. 앱에 제공할 것은 서버 등록 이름이며 검증 허용 목록을 사용자 입력으로 확장하지 않는다. Google 외의 제공자는 이 릴리스에서 활성화할 수 없다.

## 전환 순서

1. 선행 가입 스키마와 프로필 접근 제어를 확인한 뒤 V19 외부 계정·제공자 메타데이터·OAuth 요청·제한 저장소를 적용한다. 기존 이메일 가입 증명의 검증 출처는 `SERVICE`로 보존하고 기존 회원의 외부 연결은 일괄 생성하지 않는다.
2. 기존 이메일 가입·수동 생성·이관 계정의 인증 출처를 확인한다. 확실하지 않으면 Gmail 자동 연결 설정을 켜지 않고 기존 로그인 후 명시적 연결을 사용한다.
3. 웹·모바일 Google 등록, 서버 암호화 키, 고정 HTTPS 프론트 복귀 URI를 환경별로 설정한다.
4. 클라이언트에 웹 303 복귀·재발급·진행 조회, 모바일 서버 nonce를 반영한 ID 토큰·호출자 결합, 기존 회원 연결 계약을 적용한다.
5. 스테이징에서 기존 subject 로그인, Gmail 자동 연결의 허용·거절, 신규 Google 인증과 동의/프로필 이어가기, 명시적 연결, 응답 유실 후 새 인증 복구를 확인한다.
6. 준비된 환경에서 Google을 활성화한다. 신규 가입을 중지해도 이미 연결된 회원의 Google 로그인이 유지되는지 별도로 확인한다.

OAuth 요청은 시작 후 고정 10분에 만료된다. 완료된 요청·실패·처리 중 요청을 재사용하지 않는다. 가입 증명의 10분과 요청의 10분은 서로 다른 기한이며 프로필 완료 기한은 아니다.

## 장애와 중지

| 상황 | 대응 |
| --- | --- |
| 신규 가입만 중지 | `signup.enabled=false`와 `signup.legacy-signup-enabled=false`를 함께 적용. 구형 동의 우회 경로를 다시 열지 않음 |
| 신규 가입 중지 중 Google 기존 회원 | `signup.google.enabled=true`이면 기존 subject 연결 로그인을 유지. 연결이 없는 새 가입은 거절 |
| Google 검증·코드 교환 장애 | 요청은 실패 또는 재사용 불가 상태. 새 Google 로그인을 안내하고 기존 이메일 로그인 유지 |
| OAuth 연결 저장 후 완료 기록·응답 장애 | 연결은 이미 커밋되었을 수 있음. `FAILED`·`PROCESSING`·`CONSUMED`만으로 연결 취소를 추정하지 않고 새 state·nonce로 같은 Google 계정을 재인증. 기존 state 재사용·연결 삭제 금지 |
| DB 장애 | 가입·동의·예약 쓰기를 실패로 처리. Redis나 메모리로 임시 우회하지 않음 |
| 메일 장애 | 성공한 인증으로 표시하지 않음. 재발송 제한을 유지하고 클라이언트가 새 발송을 요청 |
| 기본 캐릭터 누락 | 회원·동의·연결 생성 전체 롤백. 카탈로그 복구 후 같은 유효 증명으로 재시도 |
| 닉네임 충돌·예약 만료 | 409 응답과 중복 확인 재요청. 예약 해제만 별도 커밋하지 않음 |
| OAuth 키 불일치 | 안전하게 인증 실패 처리. 인스턴스별 키 주입 일치 여부를 확인하고 새 요청으로 재시작 |

롤백은 기능 진입을 닫고 현재 스키마·데이터·프로필 접근 제어를 유지하는 방식이 우선이다. 새 상태를 모르는 구버전 바이너리로 즉시 돌아가면 미완료 회원이 회원 기능에 접근하거나 닉네임 예약을 우회할 수 있다. 새로 연결된 Google 전용 회원의 로그인 수단도 고려해야 한다. 테이블 삭제나 회원 일괄 삭제를 롤백 절차로 사용하지 않는다.

## 관찰과 실제 연동 검증

Google 검증 실패·코드 교환 실패·요청 제한·DB 잠금 대기와 연결 충돌을 관찰한다. 토큰·OAuth code·state·nonce·쿠키·비밀번호·이메일을 원문 로그에 남기지 않으며 콜백 query와 모바일 ID 토큰 본문을 수집하지 않는다.

일반 테스트와 실제 MySQL migration 태그 테스트를 구분한다. 실제 MySQL에서 OAuth subject 비교, 요청 claim·완료·재사용 방지와 연결 커밋 후 완료 기록 장애를 검증한다. Google HTTP·서명 검증과 외부 메일 발송의 테스트 대역은 운영 Google 계정·실제 메일 연동 증거가 아니다.

활성화 전 실제 Google 웹 redirect URI와 코드 교환·서명 키 조회, 모바일 registration 및 토큰 audience·authorized party·nonce, 앱 복귀·재시작 복구, 실제 웹·API 도메인의 HTTPS 쿠키·Origin·CORS·303 후 세션 복구, 모든 인스턴스의 암호화 키 일치를 확인한다. 기존 이메일 인증 출처, 약관·기본 캐릭터 등 선행 가입 준비도 확인하되 비밀값 자체는 기록하지 않는다.

## 소셜 이메일 필수 정책 전환

- 신규 Google 신원은 제공자 이메일의 존재·형식·최대 255자·기존 허용 도메인을 확인한다. 외부 이메일도 추가 코드 인증 없이 동의로 보내되 email_verified·authoritative 조건은 기존 Gmail 자동 연결에서 계속 필수다.
- 이메일 누락은 `EMAIL_REQUIRED`, 부적합은 `INVALID_EMAIL`이다. 모바일은 HTTP 400 Problem Details와 `nextAction: AUTHENTICATE`, 웹 콜백은 고정 복귀 URI의 `oauthError`로만 전달한다. 실패 시 가입 증명·회원·서비스 세션을 새로 발급하지 않는다.
- 기존 subject 로그인·재인증 명시 연결은 신규 이메일 검사와 분리한다. 같은 이메일만으로 다른 회원에 연결하지 않는다.
- 신규 증명은 바로 `AGREEMENTS`이며 소셜 이메일 보완 API·`VERIFY_EMAIL`을 제거한 클라이언트와 함께 적용한다.
