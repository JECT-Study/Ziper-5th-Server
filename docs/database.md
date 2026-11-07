# 데이터베이스 운영 기준

로컬 DB healthcheck는 `127.0.0.1` TCP로 확인합니다. 새 볼륨의 역할 초기화 중 소켓만 여는 임시 서버는 준비 완료로 처리하지 않고, 초기화 후 최종 서버가 기동되면 앱을 시작합니다.

로컬 Compose와 Testcontainers는 PostgreSQL 17.6을 사용합니다. 운영 후보인 Supabase에서도 PostgreSQL 17 계열을 확인해야 합니다. 동일한 주 버전만으로 확장 기능, 권한, 네트워크나 TLS 동작까지 같아지지는 않습니다.

| 용도 | 로컬 역할 | 권한 |
| --- | --- | --- |
| 초기 프로비저닝 | `ziper_admin` | 로컬 관리자 |
| Flyway | `ziper_migrator` | `ziper` 스키마의 DDL |
| 애플리케이션 | `ziper_app` | 업무 데이터 DML, DDL 없음 |

로컬 데이터베이스와 스키마 이름은 모두 `ziper`입니다. 앱 시작 시 Flyway가 별도 마이그레이션 자격 증명으로 실행됩니다. 런타임 계정에 DDL 권한을 추가해 마이그레이션 실패를 우회하지 않습니다. `flyway_schema_history`는 마이그레이션 전용이며 런타임 계정에는 조회·변경 권한을 주지 않습니다. 새 테이블의 기본 권한에서도 이 경계를 유지합니다. 이미 적용한 `V1`은 checksum 보존을 위해 수정하지 않고 `V2__protect_flyway_history.sql`에서 런타임의 history 권한을 회수합니다. PostgreSQL 이미지의 초기화 스크립트는 새 볼륨에서만 실행되므로 `.env`의 비밀번호를 바꿔도 기존 역할 비밀번호는 바뀌지 않습니다.

## 로컬 시드 실행

```sh
./scripts/local-seed.sh
```

이 스크립트는 인수를 받지 않고 고정된 로컬 Compose DB에만 접속합니다. `db/seed/local.sql`은 실제 도메인이 생기기 전까지 비어 있습니다. 도메인 구현 후에는 수동 실행을 위한 멱등 upsert만 추가합니다. 운영 데이터나 개인정보를 복제하지 않습니다. 시드를 부팅, Docker 이미지 빌드, Flyway에 연결하지 않습니다.

## 쿼리 시간 계측

쿼리 시간 계측은 Sentry 활성화 시 런타임 Hikari 내부의 P6Spy driver·URL prefix로 수행합니다. 환경 변수의 PostgreSQL URL·자격과 `spring.datasource.hikari`의 풀 설정을 유지합니다. Flyway는 기존 migrator 연결을 사용하며 Sentry 비활성 시에는 Boot 기본 DataSource 구성을 사용합니다. 실제 역할 분리와 풀 설정은 `TracingIntegrationTest`로 확인합니다.

`spy.properties`는 SQL 파일 로그를 비활성화합니다. Sentry에는 SQL 원문·바인딩 값·DB 이름 대신 fingerprint와 소요 시간·상태·부모 span을 남깁니다. 부모 repository/service와 코드의 prepared SQL을 대조해 원인을 찾습니다. SQL 원문 기반의 Sentry 자동 N+1 탐지나 PostgreSQL 실행 계획을 제공하지 않으며 반복 fingerprint의 개수로 직접 확인합니다. 로컬 Compose는 `SENTRY_PROFILE_SESSION_SAMPLE_RATE`도 앱에 전달하며 기본 0.0에서는 profiler를 시작하지 않습니다.

## Supabase 연결 준비

Supabase 프로젝트는 아직 없으며 `db/supabase/bootstrap.sql`도 실행하지 않았습니다. 파일은 DBA가 새 프로젝트 권한을 검토한 뒤 실행할 템플릿입니다. 실제 비밀번호는 파일에 넣지 말고 안전하게 `psql` 변수 `runtime_password`, `migration_password`로 전달합니다. 재실행 전에 기존 역할과 스키마를 확인합니다. 관리형 DB의 비슈퍼유저는 `CREATEROLE`만으로 새 migrator 역할에 `SET ROLE`할 수 있다고 가정하지 않습니다. 템플릿은 트랜잭션 안에서 migrator에 대한 임시 `SET` 멤버십을 부여하고 DBA가 전용 스키마를 생성한 뒤 `SET LOCAL ROLE`로 ACL을 설정합니다. 이후 현재 관리자가 부여한 임시 멤버십만 회수하고 커밋합니다. migrator에 DB 전체 스키마 생성 권한을 주지 않습니다. Supabase 관리 역할을 수정하지 않습니다.

1. 프로젝트의 PostgreSQL 버전과 서버 CA 인증서를 확인합니다.
2. DBA가 템플릿으로 `ziper_app`, `ziper_migrator`와 전용 `ziper` 스키마를 만듭니다.
3. 대시보드에서 Direct 또는 Session pooler 연결 문자열을 복사합니다.
4. 런타임과 마이그레이션 연결 정보를 각각 환경 변수에 설정합니다.

Direct 연결은 IPv6 도달 가능 여부를 확인합니다. IPv4 환경에서는 Session pooler의 5432 포트를 검토합니다. 사용자 이름은 대시보드 형식을 따르며 사용자 정의 역할은 보통 `ziper_app.<projectref>`, 마이그레이션 역할은 `ziper_migrator.<projectref>` 형태입니다. Transaction pooler 6543은 기본값으로 사용하지 않습니다.

`DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`는 런타임용입니다. `MIGRATION_JDBC_URL`, `MIGRATION_USERNAME`, `MIGRATION_PASSWORD`는 Flyway용으로 별도 설정합니다. 운영 JDBC URL에는 `sslmode=verify-full`과 `sslrootcert`로 서버 CA 경로를 지정해 인증서와 호스트를 검증합니다. 비밀번호나 CA 경로가 포함된 실제 연결 정보를 커밋하지 않습니다.

`ziper`는 Supabase Data API에 공개할 스키마가 아닙니다. 서버의 JWT는 JDBC를 통해 PostgreSQL RLS 신원으로 자동 전달되지 않습니다. 사용자별 접근 제어는 서버 업무 로직에서 설계하고 별도로 검증해야 합니다.
