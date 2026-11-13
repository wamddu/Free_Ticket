# 배포 (EC2 + Docker Compose)

토스 테스트 키와 테스트 데이터로 운영하는 시연용 배포 절차다.

## 구성

```
브라우저 ──HTTPS──▶ web (Caddy: 정적 파일 + /api 프록시, 인증서 자동 발급)
                      └──▶ backend (Spring Boot) ──▶ mysql-db (MySQL 8.0, 볼륨 mysql_data)
                                               └──▶ redis (공연 목록 캐시)
```

- 설정 파일: `code/backend/docker-compose.deploy.yml`, 비밀 값: 같은 폴더의 `.env.deploy`(Git에 올리지 않음)
- 보안 그룹은 SSH(내 IP), HTTP(80), HTTPS(443)만 연다. MySQL은 `127.0.0.1:3306`으로 서버 내부에만 열려 있다.
- 백엔드 이미지 빌드 중에 테스트가 실행된다. 테스트가 실패하면 기존 컨테이너가 그대로 서비스를 계속한다.

## 처음 배포

Ubuntu 24.04, 메모리 4GB, 디스크 30GB 기준이다.

1. 서버에 [Docker Engine과 Compose 플러그인](https://docs.docker.com/engine/install/ubuntu/)을 설치하고 저장소를 받는다.

   ```sh
   git clone https://github.com/wamddu/Free_Ticket.git
   cd Free_Ticket/code/backend
   cp .env.deploy.example .env.deploy
   chmod 600 .env.deploy
   nano .env.deploy
   ```

2. `.env.deploy`를 채운다.
   - `PUBLIC_HOST`: EC2 IP가 `203.0.113.10`이면 `203-0-113-10.sslip.io`
   - DB 비밀번호 두 개는 서로 다르게, `JWT_SECRET`은 `openssl rand -hex 32` 값
   - 토스 테스트 키, 네이버 지도 클라이언트 ID, 메일 발송용 네이버 계정과 앱 비밀번호
   - `DEMO_ACCOUNT_EMAILS`: README에 공개한 체험 계정 이메일. 비우면 체험 계정 보호가 꺼진다.

3. 시작한다. 빈 DB에는 Flyway가 테이블을 만든다(공연 데이터는 없음).

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml up -d --build
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml ps
   ```

4. `https://<PUBLIC_HOST>`에서 회원가입, 로그인, 공연 목록, 테스트 결제를 확인한다. 네이버 지도 콘솔의 웹 서비스 URL에도 이 주소를 등록한다.

## 재배포

아래 명령은 모두 `~/Free_Ticket/code/backend`에서 실행한다.

1. DB를 백업한다.

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml exec -T mysql-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines free_ticket' > ~/free_ticket-backup-$(date +%Y%m%d-%H%M).sql
   ```

2. 서버에서 직접 고친 파일이 없는지 확인한다. **출력이 있으면 멈춘다.** 다음 단계가 그 변경을 지운다. 서버에만 필요한 설정은 저장소에 커밋하고, 비밀 값은 `.env.deploy`에만 둔다.

   ```sh
   git status --short
   ```

3. 최신 코드를 받고 바뀐 서비스를 다시 빌드한다. 프론트가 바뀌었으면 `web`도 넣는다.

   ```sh
   git fetch origin && git reset --hard origin/main
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml up -d --build backend
   ```

4. 기동과 마이그레이션 결과를 확인한다.

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml logs backend | grep -iE "flyway|migrat|Started BackendApplication|ERROR" | tail -20
   ```

## 백업에서 복구

```sh
sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml exec -T mysql-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root free_ticket' < ~/free_ticket-backup-YYYYMMDD-HHMM.sql
```

## DB 도구로 접속

MySQL 포트는 외부에 열지 않는다. MySQL Workbench나 DBeaver에서 SSH 터널(Workbench의 "Standard TCP/IP over SSH")로 접속한다.

- SSH: EC2 주소, 사용자 `ubuntu`, 키 페어 파일
- MySQL: `127.0.0.1:3306`, `.env.deploy`의 `DB_USERNAME`/`DB_PASSWORD`

## 주의

- EC2를 중지했다가 시작하면 자동 할당 퍼블릭 IP가 바뀐다. `.env.deploy`의 `PUBLIC_HOST`를 바꾸고 `up -d --build`를 다시 실행한다. 고정 주소가 필요하면 Elastic IP를 쓰되, 중지 중에도 요금이 든다.
- `docker compose down -v`는 DB 볼륨을 지운다. 사용하지 않는다.
- EC2를 종료하기 전에는 DB를 백업한다.
