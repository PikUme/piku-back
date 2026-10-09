# piku-back

Pikume 프로젝트의 백엔드 저장소입니다.

## 🚀 시작하기

### 사전 요구 사항

- [Docker](https://www.docker.com/get-started)가 설치되어 있어야 합니다.

### 설치 및 실행

1.  **저장소를 복제합니다.**

    ```bash
    git clone https://github.com/your-username/piku-back.git
    cd piku-back
    ```

2.  **환경 변수 파일을 생성합니다.**

    프로젝트 루트 디렉토리에 `.env` 파일을 생성하고 아래 내용을 복사하여 붙여넣으세요. 이 내용은 `.env.sample`을 기반으로 합니다.

    ```bash
    # .env

    # Dev Environment
    DEV_DB_PASSWORD=your_dev_db_password

    # Prod Environment
    PROD_DB_URL=
    PROD_DB_USERNAME=
    PROD_DB_PASSWORD=

    # JWT
    JWT_KEY=your_jwt_secret_key
    ```

    - `DEV_DB_PASSWORD`: 개발 환경 데이터베이스의 비밀번호를 입력합니다.
    - `JWT_KEY`: JWT 서명에 사용할 시크릿 키를 입력합니다.

3.  **Docker Compose를 사용하여 개발 환경을 실행합니다.**

    개발 애플리케이션과 데이터베이스는 Redis, MinIO 인프라 구성과 함께 실행합니다.

    ```bash
    docker compose \
      -f docker-compose.dev.yml \
      -f docker-compose.infra.yml \
      up -d --build
    ```

    코드 변경 후 데이터베이스와 인프라는 유지하고 애플리케이션만 다시 빌드하려면 다음 명령어를 실행합니다.

    ```bash
    docker compose \
      -f docker-compose.dev.yml \
      -f docker-compose.infra.yml \
      up -d --build --no-deps app
    ```

    개발 환경을 종료할 때는 다음 명령어를 실행합니다.

    ```bash
    docker compose \
      -f docker-compose.dev.yml \
      -f docker-compose.infra.yml \
      down
    ```

    로컬 회원가입 인증 메일을 Mailpit에서 확인하려면 선택형 모드를 사용합니다. 실행과 실제 API 검증 절차는 [로컬 Mailpit 회원가입 검증 절차서](docs/runbooks/local-mailpit-signup-runbook.md)를 참고하세요.

    ```bash
    scripts/compose.sh dev-mailpit up
    ```

### 운영용 앱 실행

운영용 애플리케이션 컨테이너는 루트 `.env` 파일을 기준으로 환경변수를 주입받고 Redis, MinIO 인프라 구성과 함께 실행됩니다.

1. **운영 환경을 빌드하고 실행합니다.**

    ```bash
    docker compose \
      -f docker-compose.prod.yml \
      -f docker-compose.infra.yml \
      up -d --build
    ```

2. **운영용 앱만 다시 빌드하고 실행합니다.**

    ```bash
    docker compose \
      -f docker-compose.prod.yml \
      -f docker-compose.infra.yml \
      up -d --build --no-deps app
    ```

3. **앱 상태와 로그를 확인합니다.**

    ```bash
    docker compose \
      -f docker-compose.prod.yml \
      -f docker-compose.infra.yml \
      ps app
    docker compose \
      -f docker-compose.prod.yml \
      -f docker-compose.infra.yml \
      logs -f app
    ```

4. **운영 환경을 종료합니다.**

    ```bash
    docker compose \
      -f docker-compose.prod.yml \
      -f docker-compose.infra.yml \
      down
    ```

- 애플리케이션 로그 파일은 호스트 `./logs/application.log` 에 기록됩니다.
- `.env` 파일은 이미지에 복사되지 않고, 컨테이너 실행 시 환경변수로만 주입됩니다.

### 모니터링 실행

Prometheus와 Grafana는 애플리케이션 및 인프라와 별도의 Compose 프로젝트로 실행됩니다. 같은 머신에서 실행 중인 애플리케이션을 수집할 때는 기본 대상인 `host.docker.internal:8080`을 사용합니다.

```bash
docker compose -f docker-compose.monitor.yml up -d
```

다른 머신에서 실행 중인 애플리케이션을 수집할 때는 해당 머신의 사설 IP 주소와 애플리케이션 포트를 지정합니다.

```bash
MONITORING_TARGET=192.168.0.10:8080 \
  docker compose -f docker-compose.monitor.yml up -d
```

Prometheus 컨테이너 내부의 `localhost`는 호스트가 아닌 Prometheus 컨테이너 자신을 가리킵니다. 애플리케이션의 `MONITORING_ALLOWED_IPS`에는 Prometheus 요청이 애플리케이션에 도달했을 때 실제로 관찰되는 원본 IP 또는 CIDR을 지정해야 합니다.

모니터링을 종료할 때는 다음 명령어를 실행합니다.

```bash
docker compose -f docker-compose.monitor.yml down
```

## 🌱 개발 규칙

### 브랜치 전략

- 기능 단위로 브랜치를 생성하여 작업합니다.
- 브랜치 이름은 아래 컨벤션을 따릅니다.

#### 브랜치 이름 컨벤션

- `feature/기능명`: 새로운 기능 개발
- `fix/수정내용`: 버그 수정
- `docs/문서내용`: 문서 추가 또는 수정
- `refactor/리팩토링내용`: 코드 리팩토링

**예시:**

```bash
git checkout -b feature/login
```

### 커밋 컨벤션

커밋 메시지는 다음 형식을 따릅니다. 이를 통해 커밋 내역을 쉽게 파악하고 변경 사항을 추적할 수 있습니다.

**커밋은 관련된 이슈 번호를 포함해야 합니다.**

#### 커밋 메시지 형식

```
타입: 제목 #이슈번호

본문 (선택 사항)
```

#### 타입(Type)

- `feat`: 새로운 기능 추가
- `fix`: 버그 수정
- `docs`: 문서 수정
- `style`: 코드 포맷팅, 세미콜론 누락, 코드 변경이 없는 경우
- `refactor`: 코드 리팩토링
- `test`: 테스트 코드, 리팩토링 테스트 코드 추가
- `chore`: 빌드 업무 수정, 패키지 매니저 수정

**예시:**

```
feat: 로그인 기능 추가 #123

- 소셜 로그인 기능 구현
- JWT 토큰 발급 로직 추가
``` 
