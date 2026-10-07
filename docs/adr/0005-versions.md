# ADR-0005 버전 선택

- 상태: 채택 (2026-10-07)

| 항목 | 버전 | 출시 |
|---|---|---|
| Java | 21 LTS | 2023-09 |
| Spring Boot | 3.5.x | 2025-05 |
| Spring Cloud | 2025.0.x (Northfields) | 2025-05 |
| Gradle | 8.14 | 2025 |
| MySQL / Redis | 8.4 LTS / 7.4 | 2024 |

## 이유
- 최신(Boot 4.x, Java 25) 대신 1~2년 동안 검증된 조합을 택했다. 자료와 실무 사례가 많고, 면접관이 익숙한 버전이다.
- Java 21의 Virtual Threads를 켠다(`spring.threads.virtual.enabled`). 블로킹 I/O(JDBC, Feign) 위주인 서비스에서 스레드 풀 튜닝 없이 동시 처리량을 확보하기 위해서다.
- Spring Cloud Gateway 2025.0부터 바뀐 아티팩트(`spring-cloud-starter-gateway-server-webflux`)와 설정 prefix(`spring.cloud.gateway.server.webflux.*`)를 쓴다.
