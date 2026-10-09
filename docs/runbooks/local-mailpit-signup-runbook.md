# 로컬 Mailpit 회원가입 검증 절차서

- Status: Active
- Audience: Engineers
- Source of Truth: Yes
- Last Reviewed: 2026-10-09

## 목적

개발용 회원가입 메일을 로컬 SMTP 받은 편지함에서 확인하고 기존 Redis 기반 이메일 인증과 회원가입 API까지 점검합니다. 외부 이메일 전달과 운영 발신자 평판은 검증 범위에 포함되지 않습니다.

## 사전 조건

- Docker와 Compose 설치 필요.
- 호스트에서 임시 백엔드 JVM 실행 시 Java 17 필요.
- 개발 데이터베이스에 허용 이메일 도메인 필요.
- MinIO 고정 캐릭터 카탈로그 준비 필요.

기본 호스트 포트는 다음과 같습니다.

| 서비스 | 호스트 포트 |
| --- | ---: |
| MySQL | 9915 |
| Redis | 6379 |
| MinIO API | 9000 |
| MinIO 관리 화면 | 9001 |

### 작업 디렉터리와 설정

기본 체크아웃은 worktree를 추가하기 전의 원래 저장소 디렉터리입니다. 새 Git worktree에는 무시된 `.env` 파일과 로컬 파일이 자동 복사되지 않습니다.

Compose 도우미는 기본 체크아웃의 공유 프로젝트와 기존 데이터 경로를 사용합니다. 앱은 현재 worktree의 코드로 빌드합니다. Compose는 기본 체크아웃의 `.env`를 기존 방식대로 불러옵니다. 파일 내용을 채팅이나 로그 또는 문서에 복사하지 않습니다.

Firebase 초기화는 prod 프로필에서만 수행합니다. dev JVM 실행에는 Firebase 자격 증명 파일이 필요하지 않습니다. 이는 기존 Docker 앱의 자격 증명 바인드 마운트와 별개입니다.

데이터베이스와 객체 저장소 설정은 로컬 환경에 보관합니다. 저장소의 dev 설정에는 기본값이 있습니다. 실행 중인 서비스에 별도 값이 필요하면 로컬 환경 변수로 설정하고 공유하지 않습니다.

## 선택형 Compose 모드 실행

Mailpit 웹 UI는 `127.0.0.1:8025`에, 호스트 SMTP는 `127.0.0.1:1025`에 기본 바인딩됩니다. 포트가 사용 중이면 호스트 포트를 변경합니다.

```bash
scripts/compose.sh dev-mailpit up
```

```bash
MAILPIT_UI_PORT=18025 MAILPIT_SMTP_PORT=11025 scripts/compose.sh dev-mailpit up
```

기본 UI 주소는 `http://127.0.0.1:8025`입니다. 다른 UI 포트를 설정했다면 해당 주소를 열어 수신 메일을 확인합니다. Compose 앱의 SMTP 주소는 Docker 네트워크의 `mailpit:1025`입니다.

기존 개발 스택에서 기본값과 다른 Compose 프로젝트 이름이나 디렉터리를 사용한다면 해당 값을 지정합니다. 그러면 기존 컨테이너와 볼륨을 재사용할 수 있습니다.

```bash
DEV_COMPOSE_PROJECT_DIRECTORY=/path/to/primary/piku-back \
DEV_COMPOSE_PROJECT_NAME=piku-back \
scripts/compose.sh dev-mailpit up
```

일반 dev와 dev-mailpit은 개발 앱과 데이터베이스, Redis, MinIO 및 Compose 프로젝트를 공유합니다. 두 모드를 별도 스택으로 동시에 실행하지 않습니다.

기능 worktree에서 일반 dev로 돌아갈 때는 먼저 공유 프로젝트를 중지합니다. 같은 프로젝트와 데이터 경로를 유지하려면 기본 체크아웃에서 일반 모드를 실행합니다.

```bash
scripts/compose.sh dev-mailpit down
cd /path/to/primary/piku-back
scripts/compose.sh dev up
```

기본 체크아웃에서 일반 dev를 실행하면 같은 Compose 프로젝트와 데이터 경로를 사용합니다. 앱은 해당 체크아웃의 코드로 빌드합니다. `down`은 볼륨을 보존하지만 공유 프로젝트의 서비스는 중지합니다. Mailpit을 중지하기 전에 필요한 메일을 확인합니다. 컨테이너를 제거하면 받은 편지함도 삭제될 수 있습니다.

상태·로그 확인:

```bash
scripts/compose.sh dev-mailpit ps
scripts/compose.sh dev-mailpit logs app
scripts/compose.sh dev-mailpit logs mailpit
scripts/compose.sh dev-mailpit rebuild-app
```

## 호스트에서 임시 백엔드 실행

기존 백엔드 서비스를 교체하거나 재생성하지 않고 테스트하려면 실행 중인 MySQL과 Redis, MinIO를 사용하는 임시 dev JVM을 실행합니다. 사용하지 않는 루프백 포트에 독립 Mailpit 컨테이너를 시작합니다.

```bash
docker run --rm --detach --name piku-local-mailpit \
  --publish 127.0.0.1:1025:1025 \
  --publish 127.0.0.1:8025:8025 \
  axllent/mailpit:v1.31.4
```

포트가 사용 중이면 다른 호스트 포트를 선택하고 `SPRING_MAIL_PORT`에 SMTP 포트를 지정합니다. `--rm`으로 시작한 임시 컨테이너는 `docker stop piku-local-mailpit`으로 중지하면 제거됩니다. 기존 Supabase나 다른 Mailpit 컨테이너는 중지하지 않습니다.

백엔드 worktree에서 앱을 사용하지 않는 포트로 실행합니다. 아래 URL은 일반적인 개발 서비스 포트에 연결합니다. 저장소의 dev 설정에는 로컬 기본값이 있습니다. 데이터베이스에 별도 암호가 필요하면 이미 알고 있는 값으로 `DEV_DB_PASSWORD`를 셸에서 설정합니다. 암호를 확인하려고 로컬 비밀 파일을 열거나 출력하지 않습니다.

```bash
PROFILE=dev \
SERVER_PORT=8080 \
DEV_DB_URL=jdbc:mysql://localhost:9915/piku \
REDIS_URL=redis://localhost:6379 \
SERVER_TO_S3_URL=http://localhost:9000 \
CLIENT_TO_S3_URL=http://localhost:9000 \
SPRING_MAIL_HOST=localhost \
SPRING_MAIL_PORT=1025 \
SPRING_MAIL_USERNAME=signup-test@pikume.test \
SPRING_MAIL_PASSWORD= \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=false \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=false \
./gradlew bootRun
```

dev JVM 실행에는 Firebase 자격 증명 파일이 필요하지 않습니다. Mailpit은 SMTP 인증과 STARTTLS를 요구하지 않습니다. 메시지는 외부로 전달되지 않고 로컬에 보관됩니다.

## 실제 회원가입 인증 흐름 확인

실제 애플리케이션 API로 진행합니다. 테스트 전용 엔드포인트로 인증 코드나 토큰을 반환하지 않습니다.

1. 허용 도메인 조회: `GET /api/auth/email-domains`. 사용 가능한 고정 캐릭터 ID 조회: `GET /api/characters/fixed`의 `fixedCharacterId`.
2. 허용 도메인의 새 테스트 이메일 주소로 인증 메일 요청.

   ```bash
   curl -sS -X POST http://localhost:8080/api/auth/send-verification/sign-up \
     -H 'Content-Type: application/json' \
     -d '{"email":"signup-check@<allowed-domain>"}'
   ```

3. Mailpit에서 메일을 열고 본문의 6자리 코드 확인.
4. 코드와 `SIGN_UP` 유형을 인증 엔드포인트로 전송. 응답의 `emailVerificationToken`은 로컬에서만 보관.

   ```bash
   curl -sS -X POST http://localhost:8080/api/auth/verify-code \
     -H 'Content-Type: application/json' \
     -d '{"email":"signup-check@<allowed-domain>","code":"<mailpit-code>","type":"SIGN_UP"}'
   ```

5. 같은 이메일 주소와 응답 토큰으로 회원가입 요청. 로컬 테스트 비밀번호와 중복되지 않는 닉네임 사용. 1단계에서 확인한 고정 캐릭터 ID 포함.

   ```bash
   curl -i -X POST http://localhost:8080/api/auth/signup \
     -H 'Content-Type: application/json' \
     -d '{"email":"signup-check@<allowed-domain>","password":"<local-test-password>","nickname":"<unique-test-nickname>","fixedCharacterId":<fixed-character-id>,"emailVerificationToken":"<verification-token>"}'
   ```

회원가입 성공 응답은 HTTP `201`입니다. 인증 코드는 5분 후 만료되고 회원가입 토큰은 10분 후 만료됩니다. 메일은 시간당 5회까지 발송할 수 있으며 재발송 대기 시간은 1분입니다. 코드를 5회 잘못 입력하면 해당 코드는 사용할 수 없습니다. 가입에 성공하면 로컬 개발 데이터베이스에 사용자가 추가됩니다. 인증 코드와 토큰, 비밀번호, 테스트 계정은 커밋이나 보고서에 포함하지 않습니다.

## 종료 및 정리

공유 Compose 모드를 종료하기 전에 다른 개발 작업에서 해당 프로젝트를 사용하는지 확인합니다.

```bash
scripts/compose.sh dev-mailpit down
```

독립 실행 검증에서는 `Ctrl-C`로 임시 JVM만 종료합니다. 이번 테스트에서 시작한 Mailpit 컨테이너만 중지합니다. 데이터베이스와 Redis, MinIO 볼륨은 제거하지 않습니다. 기존 받은 편지함은 비우지 않습니다. 무관한 Compose 프로젝트는 중지하지 않습니다.
