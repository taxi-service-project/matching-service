# 🤝 Matching Service

> **승객의 요청 위치를 기반으로 최적의 기사를 탐색하고 실시간으로 배정합니다.**

## 🛠 Tech Stack
| Category | Technology                      |
| :--- |:--------------------------------|
| **Language** | **Java 17**                     |
| **Framework** | Spring WebFlux |
| **Database** | Redis (Geo/Hash - Storage Mode) |
| **Messaging** | Apache Kafka                    |

## 📡 API Specification

| Method | URI | Description |
| :--- | :--- | :--- |
| `POST` | `/api/matches` | 매칭 요청 (비동기 처리) |

## 🚀 Key Improvements
* **Latency 최적화:** Redis Geo 조회 시 `limit(50)`과 `.next()`(단락 평가)를 적용하여 검색 속도 향상.
* **Concurrency Control:** 기사 배정 즉시 Redis 상태를 `Busy(0)`로 선점하여 중복 배차 방지.
* **Reactive Pipeline:** `ReactiveRedisTemplate` 기반의 완전한 Non-blocking 처리.