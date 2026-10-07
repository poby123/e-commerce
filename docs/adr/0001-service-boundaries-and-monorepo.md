# ADR-0001 서비스 분리와 모노레포

- 상태: 채택 (2026-10-07)

## 결정
- 서비스: user, product, coupon, inventory, order, payment, notification + gateway, discovery, config.
- **Database per Service**: 서비스마다 스키마와 DB 계정을 따로 둔다(`docker/mysql/init`). 다른 서비스의 테이블은 계정 권한상 읽을 수 없다. 로컬에서는 MySQL 컨테이너 하나를 스키마로 나눠 쓴다.
- 저장소는 **하나(모노레포) + Gradle 멀티모듈**. 공통 빌드 규칙은 `build-logic/`의 convention plugin으로 관리한다.

## 이유
- 쿠폰을 재고와 분리한 이유: 수명주기(발급→예약→사용)와 트래픽 형태(선착순 폭증)가 다르다.
- 모노레포를 택한 이유: 1인 프로젝트에서 서비스 간 이벤트 스키마 변경을 한 커밋으로 맞출 수 있다. 배포 단위는 모듈별 이미지로 이미 분리되어 있다.

## 감수한 점
- `common`이 비대해지면 서비스 간 결합이 생긴다. 그래서 `common`에는 에러 포맷, 인증 헤더, (이후) 이벤트 계약만 넣고 도메인 로직은 넣지 않는다.
