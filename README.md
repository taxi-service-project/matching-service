# MSA 기반 Taxi 호출 플랫폼 - Matching Service

Taxi 호출 플랫폼의 **핵심 매칭 로직**을 담당하는 마이크로서비스입니다. 승객의 호출 요청을 받아, **비동기적으로** 주변의 '운행 가능한' 기사를 검색하고 최적의 기사를 매칭합니다. 매칭 성공 시 Kafka로 `TripMatchedEvent`를 발행합니다. Spring WebFlux/Reactor 기반의 Reactive 스택으로 구현되었습니다.

## 주요 기능

* **매칭 요청 처리:**
    * `POST /api/matches`
    * 승객의 호출 정보(`MatchRequest`)를 받아 매칭 프로세스를 **비동기적으로** 시작합니다.
* **최적 기사 검색 (Asynchronous Logic):**
    1.  **Geospatial Service**(`LocationServiceClient`)를 호출하여 출발지 주변 기사를 검색합니다 (1km -> 2km -> 3km 순차적 확장).
    2.  검색된 기사들의 실시간 운행 가능 상태를 **Redis**에서 조회하여 필터링합니다.
    3.  운행 가능한 기사 중 가장 가까운 기사를 선정합니다.
* **매칭 결과 이벤트 발행:**
    * 최적 기사가 선정되면, `TripMatchedEvent` 메시지를 **Kafka** 토픽으로 발행(Produce)하여 다른 서비스에 알립니다.

## 기술 스택 (Technology Stack)

* **Language & Framework:** Java, Spring Boot, **Spring WebFlux**
* **Inter-service Communication:** **Spring WebClient**
* **Cache/State Store:** Spring Data Redis
* **Messaging:** Spring Kafka
* **Service Discovery:** Spring Cloud Netflix Eureka Client
