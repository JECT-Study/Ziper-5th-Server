# 0012 운영 비밀의 단일 원본을 Secret Manager 고정 버전으로 전환

- 상태: 채택 — 실제 운영 활성화는 Infra 실행별 증거로 확인
- 날짜: 2026-11-15
- 관계: [ADR 0006](0006-initial-production-deployment.md)의 "홈 호스트 환경 파일이 운영 설정의 기준"을 대체한다. [ADR 0002](0002-infrastructure-contract.md)의 사용자 정의 `ConfigDataLoader`는 채택하지 않는다. 구현과 운영 절차는 [Infra ADR 0015](https://github.com/JECT-Study/Ziper-5th-Infra/blob/main/docs/adr/0015-secret-manager-single-source.md)가 소유한다.

## 배경

운영 비밀값이 운영자 노트북, Secret Manager, 홈서버 `/opt/ziper/private/runtime.env` 세 곳에 복사되어 있었다. Cloud Run만 Secret Manager를 읽었고 홈은 수동 복사본을 compose `env_file`로 읽었다. 2026-11-15 세 사본의 키 이름과 값 해시를 대조해 모두 일치함을 확인했지만, 교체할 때마다 세 곳을 따로 갱신해야 했다.

## 결정

- Secret Manager의 고정 버전을 홈과 Cloud Run의 단일 원본으로 둔다. 버전 번호는 Infra `config/production.json`의 `secretVersions`가 정하며 `latest`를 읽지 않는다.
- 앱은 지금처럼 환경 변수 이름의 placeholder(`${DB_PASSWORD}` 등)만 참조한다. 두 origin 모두 `SPRING_CONFIG_IMPORT`로 같은 형식의 properties 파일을 읽는다. 홈은 마이그레이션 계정을 담은 두 번째 파일을 함께 가져온다.
- 홈에는 GCP 자격 증명을 두지 않는다. Infra의 deploy job이 WIF로 읽어 제한 SSH 수신기로 전달한다.
- 앱 코드와 Spring 설정 파일은 바꾸지 않는다. 사용자 정의 Secret Manager 로더를 만들지 않는다.
- 로컬 개발 비밀은 범위 밖이다. `scripts/local-setup.sh`가 PC별 무작위 값을 계속 생성한다.

## 대안

- 앱이 Secret Manager를 직접 읽는 로더(ADR 0002 원안): 앱이 부팅마다 GCP 자격과 네트워크에 의존하게 된다. 홈 장애 대비용 Cloud Run과 같은 원인으로 함께 실패할 수 있다.
- 홈 호스트 파일을 원본으로 유지: 교체 시 세 사본의 어긋남을 막지 못한다.

## 영향과 한계

- 같은 이미지를 `env_file` 없이 properties 파일 두 개만으로 prod 프로필에서 실행해 Flyway 검증과 `/readyz` 200을 확인했다. 두 번째 파일을 빼면 마이그레이션 계정 placeholder가 풀리지 않아 기동에 실패한다. 이는 로컬 DB 검증이며 운영 활성화 증거가 아니다.
- 운영 활성화에는 Infra 병합, GCP 부트스트랩 이관, 홈 제어 파일 설치와 이 저장소 `deploy-production.yml`의 Infra pin 갱신이 같은 배포 창에서 필요하다.
- GitHub Secrets, Cloudflare Worker Secrets와 홈 Tunnel 토큰은 이번 단일 원본 범위 밖이다.
