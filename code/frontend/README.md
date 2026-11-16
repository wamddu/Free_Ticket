# Free Ticket 프론트엔드

React 19 + TypeScript + Vite + Tailwind CSS. 프로젝트 소개와 전체 구성은 [루트 README](../../README.md)를 본다.

## 실행

백엔드(`code/backend`의 `docker compose up -d --build`)가 `http://localhost:8080`에서 떠 있어야 한다.

```sh
cp .env.example .env   # 토스 테스트 클라이언트 키, 네이버 지도 클라이언트 ID 입력
npm ci
npm run dev            # http://localhost:5173
```

| 변수 | 내용 |
|---|---|
| `VITE_BACKEND_URL` | 백엔드 API 주소. 로컬은 `http://localhost:8080/api`, 배포 이미지는 `/api`(Caddy가 프록시) |
| `VITE_TOSS_CLIENT_KEY` | 토스페이먼츠 결제위젯 테스트 클라이언트 키 |
| `VITE_NAVER_MAP_CLIENT_ID` | 네이버 지도 클라이언트 ID. 네이버 클라우드 콘솔에 접속할 사이트 주소를 등록해야 지도가 뜬다 |

`VITE_` 값은 빌드 결과에 그대로 들어가 브라우저에 보이므로 비밀 값을 넣지 않는다.

## 명령

| 명령 | 내용 |
|---|---|
| `npm run dev` | 개발 서버 |
| `npm run lint` | ESLint |
| `npm test` | Vitest |
| `npm run build` | 타입 검사 후 `dist/`로 빌드 |

CI는 `lint` → `test` → `build`를 실행한다. 배포 이미지는 [Dockerfile.deploy](Dockerfile.deploy)로 빌드한 정적 파일을 Caddy가 제공한다.
