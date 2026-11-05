# 0006 홈서버 첫 운영 배포와 Uptime 검증

- 상태: 채택; 운영 기동과 외부 Uptime 검증 진행
- 날짜: 2026-11-05

## 배경

사용자는 로컬 Sentry 수신 확인에 이어 실제 운영 서버 배포와 Uptime 검증을 요청했습니다. 운영 홈서버는 기존 `ssh.joonamin.dev`의 Windows 장비이며 Ubuntu WSL에서 Linux Docker 엔진을 사용할 수 있습니다. Supabase에는 집어 전용 `Zip-er` 프로젝트가 있습니다. 기존 tocktock 컨테이너와 Tunnel도 같은 장비에서 운영 중입니다.

[ADR 0004](0004-deployment-and-failover.md)의 Cloud Run 보조 origin, 회로와 GCP 비밀 로더는 아직 구현되지 않았습니다. 원격 `main`에는 초기 README만 있어 첫 기동에 그 브랜치의 배포 워크플로를 사용할 수 없습니다.

## 결정

첫 운영 배포는 홈서버에서 시작합니다. 최신 `develop`을 기준으로 별도 작업 디렉터리에서 집어 지표 수집과 운영 구성을 통합하고 소스 커밋을 만듭니다. 검사한 JAR를 재컴파일 없이 release 이미지에 넣으며 JAR 해시·Sentry debug ID를 검증합니다. 작업자가 기존 SSH 접속으로 이 이미지를 전달하고 `deployment/home/deploy.sh`를 수동 실행합니다. 이 첫 기동은 ADR 0004의 정식 `main` GitHub Actions 배포 경로를 적용하기 전의 부트스트랩이며 브랜치 보호를 변경하지 않습니다.

전용 Compose 프로젝트 이름은 `ziper-production`입니다. 별도 Cloudflare Tunnel과 `api-ziper.joonamin.dev`를 사용하고, 서비스 경로는 Docker 내부의 `http://app:8080`입니다. 공개 인바운드 포트를 열지 않습니다. 기존 컨테이너·Tunnel·nginx 설정은 변경하지 않습니다.

운영 DB는 기존 Supabase 프로젝트의 Session pooler 5432를 사용합니다. `ziper_app`은 스키마 사용과 업무 DML, `ziper_migrator`는 전용 스키마 DDL만 담당합니다. JDBC는 공식 루트 CA와 `sslmode=verify-full`로 서버를 검증합니다.

초기 비밀 값은 홈서버 Ubuntu의 `/opt/ziper/private`에 저장합니다. 디렉터리 권한은 `0700`, 런타임 환경 파일은 `0600`입니다. Tunnel 토큰은 파일로만 전달하고 이미지·명령 인수·저장소에 포함하지 않습니다. Sentry 환경은 `prod`, 지표 origin은 `home`입니다. GCP Secret Manager JSON 로더와 키는 이번 단계에 도입하지 않으며 그 전까지 이 호스트 파일이 운영 설정의 기준입니다.

배포 스크립트는 파일 잠금으로 직렬 실행하고, 기록한 이미지 ID를 대조한 뒤 최대 3분 동안 DB를 포함한 `/readyz`를 기다립니다. 새 버전이 실패하면 이전 이미지가 있는 경우에만 직전 버전으로 되돌리고 실패를 반환합니다. 공개 HTTPS 점검과 Sentry Uptime 수신은 별도로 확인합니다.

## 대안

- 전체 장애 전환 구성을 먼저 도입: 아직 없는 GCP·IAM·회로 구현 때문에 요청한 첫 운영 점검 범위가 커져 후속 단계로 남깁니다.
- 기존 tocktock 컨테이너와 Tunnel 재사용: 변경과 재시작이 다른 프로젝트에 영향을 줄 수 있어 별도로 구성합니다.
- 홈서버에서 소스 재빌드: 검사한 JAR와 소스 문맥의 연결을 유지하기 위해 사용하지 않습니다.

## 영향과 한계

Cloud Run 자동 장애 전환은 아직 없습니다. 홈서버나 공용 DB가 중단되면 서비스도 중단됩니다. 컨테이너 교체 중 짧은 중단이 생길 수 있으며 롤백은 DB 마이그레이션의 이전 버전 호환성을 전제로 합니다.

`unless-stopped`는 Docker 엔진 실행 후의 컨테이너 재시작 정책입니다. Windows 재부팅 후 Docker Desktop과 WSL이 자동으로 준비되는지는 이번 작업에서 재부팅으로 검증하지 않습니다.

초기 호스트 환경 파일 방식은 GCP 비밀 로더 도입 시 대체합니다. 실제 배포 커밋, 이미지 식별자, 외부 HTTP와 Sentry 장애·복구 결과는 [검증 이력](../verification.md)에 기록합니다. 지정 Discord 서버에는 봇 설치 권한이 없어 알림 전달 검증이 여전히 필요합니다.
