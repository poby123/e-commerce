# ADR-0004 인증: Gateway에서 JWT 검증

- 상태: 채택, 구현 완료 (2026-10-07)

## 결정
- user-service가 **RS256**으로 Access Token에 서명하고, 공개키를 `/.well-known/jwks.json`으로 공개한다.
- gateway만 토큰을 검증한다(JWKS는 Eureka 경유로 가져와 캐시). 검증에 성공하면 `X-User-Id`, `X-User-Roles` 헤더를 붙여 내부 서비스로 넘긴다.
- **클라이언트가 보낸 `X-User-*` 헤더는 항상 제거한다**(`UserContextFilter`). 내부 서비스는 외부 포트를 열지 않는다(compose에서 gateway만 노출).
- Refresh Token은 JWT가 아닌 불투명 랜덤 값이고, Redis에 **SHA-256 해시만** 저장한다. Rotation을 적용하고, 이미 쓴 토큰이 다시 오면 같은 로그인 계열 전체를 폐기한다(재사용 탐지). 세션 수명은 로그인 시점부터 14일로 고정(절대 만료)한다.

## 이유
- HS256(공유 비밀키)이면 gateway도 토큰을 발급할 수 있는 키를 갖게 된다. RS256이면 발급 권한은 user-service에만 있다. kid를 키 thumbprint로 두므로, 키를 교체하면 gateway가 모르는 kid를 만나 JWKS를 다시 가져온다.
- Access Token을 짧게(15분) 두는 이유: 블랙리스트 없이는 만료 전에 폐기할 수 없다.

## 감수한 점 / 이후 과제
- 내부망을 신뢰하는 모델이다. Zero Trust가 필요하면 각 서비스도 JWT를 검증하거나 mTLS를 도입한다.
- 같은 Refresh Token으로 동시에 두 번 요청하면(탭 2개) 재사용으로 판정되어 로그아웃된다. 필요하면 짧은 유예 시간을 둔다.
- 로그인 실패: 없는 이메일도 더미 해시와 비교해 응답 시간을 맞추고, 같은 에러 코드를 돌려준다(계정 존재 여부 노출 방지).
- 서명 키는 `ecommerce.jwt.private-key-location`으로 고정 키를 주입한다(k8s에서는 Secret 마운트). 키가 없으면 **기동이 실패한다**. 임시 키 생성은 `allow-generated-key: true`로 명시한 로컬 개발(config-repo)에서만 허용한다. 임시 키는 인스턴스마다 달라서, replica가 2개 이상이면 로그인이 무작위로 실패하기 때문이다.
  - 검증: user-service 2개가 같은 키 파일로 같은 kid를 공개하고 E2E를 통과하는 것을 확인했다(2026-10-07). `SigningKeyConfigTest`가 같은 상황을 재현한다.
