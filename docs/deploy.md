# 배포 (AWS Lightsail + Docker Compose)

토스 테스트 키와 테스트 데이터로 운영하는 시연용 배포 절차다. 서버는 AWS Lightsail 인스턴스(Ubuntu 24.04) 한 대다.

## 구성

```
브라우저 ──HTTPS──▶ web (Caddy: 정적 파일 + /api 프록시, 인증서 자동 발급)
                      ├──▶ backend :8080 (Spring Boot) ──▶ mysql-db (MySQL 8.0, 볼륨 mysql_data)
                      │                               └──▶ redis (공연 목록 캐시)
                      ├──▶ backend :8081 /actuator/health   (/health 경로만)
                      └──▶ grafana (/grafana) ──▶ prometheus ──15초마다 수집──▶ backend :8081 /actuator/prometheus
외부 감시: UptimeRobot ──5분마다──▶ https://<PUBLIC_HOST>/health
```

- 설정 파일: `code/backend/docker-compose.deploy.yml`, 비밀 값: 같은 폴더의 `.env.deploy`(Git에 올리지 않음)
- Lightsail 방화벽(인스턴스 → 네트워킹 → IPv4 방화벽)은 SSH(22), HTTP(80), HTTPS(443)만 연다. SSH는 내 IP로 제한한다. MySQL은 `127.0.0.1:3306`으로 서버 내부에만 열려 있다.
- 평소 배포는 [자동 배포](#자동-배포-cd)가 한다. 서버에서 직접 빌드할 때(`up -d --build`)는 이미지 빌드 중에 테스트가 실행되고, 실패하면 기존 컨테이너가 그대로 서비스를 계속한다.

## 처음 배포

**서버를 새로 만들 때만 쓰는 절차다.** 운영 중인 서버에서 다시 실행하면 비밀 값을 예시 값으로 덮어쓸 수 있다. 업데이트는 [자동 배포](#자동-배포-cd)나 [수동 재배포](#수동-재배포)를 쓴다.

Ubuntu 24.04, 메모리 4GB, 디스크 30GB 기준이다.

1. 서버에 [Docker Engine과 Compose 플러그인](https://docs.docker.com/engine/install/ubuntu/)을 설치하고 저장소를 받는다.

   ```sh
   git clone https://github.com/wamddu/Free_Ticket.git
   cd Free_Ticket/code/backend
   # -n: 이미 .env.deploy가 있으면 덮어쓰지 않는다.
   cp -n .env.deploy.example .env.deploy
   chmod 600 .env.deploy
   nano .env.deploy
   ```

2. `.env.deploy`를 채운다.
   - `PUBLIC_HOST`: 서버 공인 IP가 `203.0.113.10`이면 `203-0-113-10.sslip.io`
   - DB 비밀번호 두 개는 서로 다르게, `JWT_SECRET`은 `openssl rand -hex 32` 값
   - 토스 테스트 키, 네이버 지도 클라이언트 ID, 메일 발송용 네이버 계정과 앱 비밀번호
   - `DEMO_ACCOUNT_EMAILS`: README에 공개한 체험 계정 이메일. 비우면 체험 계정 보호가 꺼진다.
   - `GRAFANA_ADMIN_PASSWORD`: Grafana 관리자(`admin`) 비밀번호. Grafana를 처음 띄울 때만 쓰인다. 이후 변경은 Grafana 화면에서 한다.

3. 시작한다. 빈 DB에는 Flyway가 테이블을 만든다(공연 데이터는 없음).

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml up -d --build
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml ps
   ```

4. `https://<PUBLIC_HOST>`에서 회원가입, 로그인, 공연 목록, 테스트 결제를 확인한다. 네이버 지도 콘솔의 웹 서비스 URL에도 이 주소를 등록한다.

## 자동 배포 (CD)

main에 푸시하면 CI가 끝난 뒤 `Deploy` 워크플로([deploy.yml](../.github/workflows/deploy.yml))가 이어서 실행된다.

```
main 푸시 → CI(테스트) 통과
  → Deploy: 커밋마다 backend·web 이미지를 빌드해 GHCR에 올림 (ghcr.io/wamddu/free-ticket-*:<커밋>)
  → AWS SSM Run Command로 서버에서 code/backend/deploy.sh <커밋> 실행
       ├ 서버에서 직접 고친 파일이 있으면 중단
       ├ DB 백업 (~/backups, 최근 10개 보관)
       ├ 해당 커밋으로 이동, 이미지 받아서 교체
       ├ /health가 UP이 될 때까지 최대 4분 대기
       └ 실패하면 이전 커밋과 이전 이미지로 되돌림
```

- 서버의 SSH 포트를 GitHub에 열지 않는다. GitHub Actions는 OIDC 토큰으로 AWS 임시 권한을 받아 SSM에 명령만 보낸다. 저장소에 AWS 키를 두지 않는다.
- 서버의 SSM 에이전트가 AWS에 먼저 연결해 명령을 받아 가는 방식이라, 서버로 들어오는 포트가 필요 없다.
- 테스트는 CI에서 한 번만 실행한다(이미지 빌드 때는 `RUN_TESTS=false`).
- 수동 재배포가 필요하면 Actions 탭 → Deploy → Run workflow로 main의 최신 커밋을 다시 배포한다.
- 되돌린 경우 실패한 버전이 마이그레이션을 실행했을 수 있다. 로그에 나온 백업 파일로 복구할지 확인한다.

### 처음 한 번 설정

리전은 서울(`ap-northeast-2`) 기준이다. `<계정ID>`(12자리)는 콘솔 오른쪽 위 계정 메뉴에서 확인한다. 서버 안에서 보이는 계정 번호(인스턴스 역할 ARN의 번호)는 Lightsail이 쓰는 계정이라 다르다.

**1. 서버를 SSM 관리 노드로 등록 (하이브리드 활성화)**

Lightsail 인스턴스는 EC2와 달리 IAM 역할을 직접 붙일 수 없다. 그래서 AWS 밖의 서버와 같은 방식(하이브리드 활성화)으로 SSM에 등록한다. 서버 1대는 무료 범위(기본 티어)다.

1. Systems Manager → 하이브리드 활성화 → 활성화 생성
   - 인스턴스 제한 `1`, IAM 역할은 "시스템에서 생성한 기본 역할 사용"
   - 생성 직후 한 번만 보이는 **Activation Code**와 **Activation ID**를 따로 적어 둔다. 남에게 공유하지 않는다.
2. 서버에서 등록한다. Ubuntu에는 에이전트가 snap으로 설치돼 있다.
   ```sh
   sudo snap stop amazon-ssm-agent
   sudo /snap/amazon-ssm-agent/current/amazon-ssm-agent -register -code "<Activation Code>" -id "<Activation ID>" -region "ap-northeast-2" -y
   sudo snap start amazon-ssm-agent
   ```
3. 등록된 노드 ID(`mi-`로 시작)를 확인한다.
   ```sh
   sudo cat /var/lib/amazon/ssm/registration
   ```
4. Systems Manager → Fleet Manager에 그 노드가 "온라인"으로 보이면 된다(1~2분). 안 보이면 `sudo snap restart amazon-ssm-agent` 후 `/var/log/amazon/ssm/amazon-ssm-agent.log`를 확인한다.

**2. GitHub를 AWS에 신원 공급자로 등록**

IAM → 자격 증명 공급자 → 공급자 추가 → OpenID Connect
- 공급자 URL: `https://token.actions.githubusercontent.com`
- 대상: `sts.amazonaws.com`

**3. GitHub Actions가 쓸 배포 역할**

IAM → 역할 → 역할 생성 → "사용자 지정 신뢰 정책"에 아래를 넣는다. 이 저장소의 main 브랜치에서 실행된 워크플로만 이 역할을 쓸 수 있다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Federated": "arn:aws:iam::<계정ID>:oidc-provider/token.actions.githubusercontent.com"},
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:wamddu@169367562/Free_Ticket@1452830219:ref:refs/heads/main"
      }
    }
  }]
}
```

`sub`의 `@숫자`는 GitHub 소유자와 저장소의 고유 ID다. GitHub가 이 형식으로 토큰을 보내는 저장소에서 `repo:wamddu/Free_Ticket:...`처럼 이름만 쓰면 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 거절된다. ID가 들어 있어서 같은 이름으로 저장소를 다시 만들어도 이 역할을 쓸 수 없다. 저장소를 옮겼거나 거절될 때는 CloudTrail → 이벤트 기록 → 이벤트 이름 `AssumeRoleWithWebIdentity`의 `userIdentity.userName`에서 실제 값을 확인해 그대로 넣는다.

권한 단계는 건너뛰고 이름 `free-ticket-github-deploy`로 생성한다. 만든 역할 → 권한 추가 → 인라인 정책 생성 → JSON에 아래를 넣고 이름 `ssm-deploy`로 저장한다. 이 노드 한 대에 셸 명령을 보내고 결과를 읽는 권한만 준다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ssm:ap-northeast-2:<계정ID>:managed-instance/<mi-노드ID>",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"],
      "Resource": "*"
    }
  ]
}
```

**4. GitHub 저장소 변수**

저장소 → Settings → Secrets and variables → Actions → **Variables** 탭 → New repository variable. 비밀 값이 아니므로 Secrets가 아니라 Variables에 넣는다.

| 이름 | 값 |
|---|---|
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_DEPLOY_ROLE_ARN` | 3에서 만든 역할의 ARN (`arn:aws:iam::<계정ID>:role/free-ticket-github-deploy`) |
| `SSM_NODE_ID` | 1에서 확인한 `mi-` 노드 ID (예전 이름 `EC2_INSTANCE_ID`도 읽는다) |
| `VITE_TOSS_CLIENT_KEY` | `.env.deploy`의 같은 이름 값 (브라우저에 그대로 노출되는 테스트 클라이언트 키) |
| `VITE_NAVER_MAP_CLIENT_ID` | `.env.deploy`의 같은 이름 값 |

**5. 첫 실행 후 이미지 공개**

첫 Deploy 실행에서 이미지가 GHCR에 올라간 뒤, GitHub 프로필 → Packages → `free-ticket-backend`와 `free-ticket-web` 각각 → Package settings → Change visibility → Public. 이미지에는 비밀 값이 없다(서버의 `.env.deploy`에서 실행 시 주입). 공개 전에는 서버가 이미지를 받지 못해 첫 배포가 실패한다(서버는 아무것도 바뀌지 않는다). 공개한 뒤 Actions에서 실패한 Deploy 실행을 Re-run 한다.

1~4를 마치기 전에는 Deploy 워크플로가 실패하지 않고 건너뛴다.

### 설정하며 겪은 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| Fleet Manager에 서버가 안 보임 | Lightsail 인스턴스에는 `AmazonLightsailInstanceRole`이 붙어 있고 바꿀 수 없다. 에이전트 로그에 `not authorized to perform: ssm:UpdateInstanceInformation` | 하이브리드 활성화로 등록(위 1) |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 토큰의 `sub`가 `repo:wamddu@<ID>/Free_Ticket@<ID>:...` 형식이었다 | CloudTrail에서 실제 `sub`를 확인해 신뢰 정책에 반영(위 3) |
| `not authorized to perform: ssm:SendCommand on resource: ...managed-instance/mi-...` | 정책 Resource가 EC2 형식(`instance/i-...`)이었다 | `managed-instance/<mi-노드ID>`로 변경 |
| 서버가 이미지를 받지 못함 | GHCR 패키지가 비공개 | 패키지를 Public으로 변경(위 5) |

## 수동 재배포

자동 배포를 쓸 수 없을 때의 절차다. 아래 명령은 모두 `~/Free_Ticket/code/backend`에서 실행한다.

1. DB를 백업한다.

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml exec -T mysql-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -u root --single-transaction --routines free_ticket' > ~/free_ticket-backup-$(date +%Y%m%d-%H%M).sql
   ```

2. 서버에서 직접 고친 파일이 없는지 확인한다. **출력이 있으면 멈춘다.** 다음 단계가 그 변경을 지운다. 서버에만 필요한 설정은 저장소에 커밋하고, 비밀 값은 `.env.deploy`에만 둔다.

   ```sh
   git status --short
   ```

3. 최신 코드를 받고 바뀐 서비스를 다시 빌드한다. 프론트나 `Caddyfile`이 바뀌었으면 `web`도 넣는다. 서비스가 새로 추가된 경우(예: 모니터링)는 서비스 이름 없이 `up -d --build`로 전체를 올린다.

   ```sh
   git fetch origin && git reset --hard origin/main
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml up -d --build backend
   ```

4. 기동과 마이그레이션 결과를 확인한다.

   ```sh
   sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml logs backend | grep -iE "flyway|migrat|Started BackendApplication|ERROR" | tail -20
   ```

## 모니터링

| 주소 | 내용 | 공개 범위 |
|---|---|---|
| `https://<PUBLIC_HOST>/grafana` | 대시보드 "Free Ticket 운영 현황" | 로그인 없이 보기만 가능. 수정은 `admin` 로그인 |
| `https://<PUBLIC_HOST>/health` | 백엔드 상태 `UP`/`DOWN`만 | 공개 (외부 감시용) |
| `backend:8081/actuator/prometheus` | 메트릭 원본 | Docker 네트워크 안에서만. Caddy가 넘기지 않는다 |

- 대시보드와 데이터 소스는 `code/backend/monitoring/`의 파일로 만든다. 서버를 새로 띄워도 같은 화면이 나온다. 화면에서 고친 내용은 저장되지 않으므로 JSON 파일을 고쳐 커밋한다.
- Prometheus는 7일 또는 1GB까지만 보관한다(볼륨 `prometheus_data`). 메모리 상한은 Prometheus 512MB, Grafana 256MB다.
- 패널: 서버 상태, 초당 요청, 5xx 비율, p95 응답 시간, Tomcat 연결·스레드, DB 커넥션 풀, JVM 힙·GC·CPU, 결제 승인 결과, 결제 복구 대기·사람 확인 필요 건수
- 결제 지표 `freeticket_payments_*`는 Prometheus가 수집할 때마다 DB에서 센다. 복구 대기(`unfinished`)가 줄지 않으면 토스나 DB 장애가 이어지는 중이고, 사람 확인 필요(`review_required`, `reconciliation_open`)가 1 이상이면 관리자 화면에서 처리한다.

### 외부 감시 (UptimeRobot)

Prometheus는 같은 서버 안에 있어서 서버가 통째로 멈추면 알리지 못한다. 그래서 바깥에서 따로 확인한다.

1. UptimeRobot에서 New Monitor → HTTP(s), URL `https://<PUBLIC_HOST>/health`, 간격 5분
2. 키워드 감시를 쓸 경우 `"status":"UP"`이 있는지 확인하게 한다. DB가 끊기면 `/health`가 503을 돌려준다.
3. 알림 받을 메일을 등록한다. 서버 IP가 바뀌면 이 URL도 바꾼다.

## 백업에서 복구

자동 배포는 매번 `~/backups/free_ticket-<시각>-<커밋>.sql`에 백업을 남긴다(최근 10개).

```sh
sudo docker compose --env-file .env.deploy -f docker-compose.deploy.yml exec -T mysql-db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root free_ticket' < ~/free_ticket-backup-YYYYMMDD-HHMM.sql
```

## DB 도구로 접속

MySQL 포트는 외부에 열지 않는다. MySQL Workbench나 DBeaver에서 SSH 터널(Workbench의 "Standard TCP/IP over SSH")로 접속한다.

- SSH: 서버 공인 IP, 사용자 `ubuntu`, Lightsail SSH 키 파일
- MySQL: `127.0.0.1:3306`, `.env.deploy`의 `DB_USERNAME`/`DB_PASSWORD`

## 주의

- 고정 IP(Static IP)를 붙이지 않은 Lightsail 인스턴스는 중지했다가 시작하면 공인 IP가 바뀐다. 바뀌면 `.env.deploy`의 `PUBLIC_HOST`, 네이버 지도 콘솔의 웹 서비스 URL, UptimeRobot 주소를 함께 바꾸고 `up -d --build`를 다시 실행한다. Lightsail 네트워킹 탭에서 고정 IP를 만들어 붙이면 바뀌지 않는다(인스턴스에 붙지 않은 고정 IP는 요금이 나올 수 있다).
- `docker compose down -v`는 DB 볼륨을 지운다. 사용하지 않는다.
- 인스턴스를 삭제하기 전에는 DB를 백업한다.
