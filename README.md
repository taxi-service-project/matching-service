# 🤝 Matching Service

> **승객의 요청 위치를 기반으로 최적의 기사를 탐색하고, 중복 배차 없이 실시간으로 배정합니다.**

## 🛠 Tech Stack
| Category | Technology |
| :--- | :--- |
| **Language** | **Java 17** |
| **Framework** | Spring Boot (WebFlux + MVC Hybrid)
| **Database** | Redis (Reactive, Hash/Geo), MySQL (Spring Data JPA) |
| **Messaging** | Apache Kafka (Producer/Consumer) |
| **Others** | ShedLock (분산 락 스케줄러) |

## 📡 API Specification

| Method | URI | Auth | Description |
| :--- | :--- | :---: | :--- |
| `POST` | `/api/matches` | 🔐 | 매칭 요청 (1km -> 2km -> 3km 순차 반경 검색 및 배차) |

*💡 매칭 완료 후, 기사 상태의 원복(운행 종료/취소)은 `trip_events` (Kafka) 수신을 통해 비동기로 이루어집니다.*

## 🚀 Key Improvements (핵심 기술적 개선)

### 1. 분산 환경의 동시성 제어 (Redis Distributed Lock)
* **중복 배차 원천 차단:** 여러 승객이 동시에 같은 기사에게 매칭을 요청할 때 발생하는 동시성 이슈를 해결하기 위해 Redis 분산 락(`setIfAbsent`)을 도입했습니다.
* **Double-Check Locking:** 락을 획득한 후에도 기사의 상태값(`isAvailable`)을 다시 한번 검증하는 이중 확인 로직을 통해, 다른 스레드가 GC 등으로 잠시 서버가 멈췄다 로직을 다시 이어가 락이 풀린걸 보고 배차 중복이 발생하는 엣지 케이스를 방어했습니다.

### 2. Transactional Outbox Pattern (비동기 정합성 보장)
* **안전한 이벤트 발행:** Redis 상태 변경(기사 배정)과 Kafka 이벤트(`TripMatchedEvent`) 발행 사이의 분산 트랜잭션 문제를 해결하기 위해 **Outbox 패턴**을 적용했습니다. 이벤트 발행 실패 시 기사 상태(Redis)를 즉시 원복하여 데이터 정합성을 유지합니다.
* **릴레이 스케줄러 최적화:** 카프카 전송을 담당하는 `MatchingOutboxRelay`는 `FOR UPDATE SKIP LOCKED`를 통해 다중 서버에서도 DB 락 경합 없이 빠르고 안전하게 이벤트를 폴링합니다.

### 3. 고가용성 복구 메커니즘 (Zombie Cleaner)
* **좀비 기사 상태 복구:** 시스템 장애나 네트워크 오류로 인해 Redis의 기사 상태가 영원히 '0(운행 중)'으로 멈춰버리는 현상(좀비)을 해결하기 위해 `DriverStatusScheduler`를 도입했습니다.
* **교차 검증:** 스케줄러가 정기적으로 Trip Service API(`isDriverOnTrip`)를 호출해 실제 운행 여부를 교차 검증하고, 불일치 시 기사 상태를 '1(대기 중)'로 강제 복구하여 가용성을 극대화합니다.


----------

## 아키텍쳐
<img width="2324" height="1686" alt="Image" src="https://github.com/user-attachments/assets/81a25ff9-ee02-4996-80d3-f9217c3b7750" />
