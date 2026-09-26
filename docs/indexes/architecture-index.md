# Architecture Index

- Status: Active
- Audience: Engineers
- Source of Truth: Yes
- Last Reviewed: 2026-09-07

## 목적

이 문서는 `docs/architecture/` 아래의 현재 기준 문서를 찾기 위한 색인이다.

## Active Documents

- [관리자 세션 인증 흐름](../architecture/admin-session-authentication-flow.md): 정식 로그인, 최초 온보딩, 세션 단계 전환의 백엔드 책임과 흐름
- [일반 회원 인증과 단계별 가입 흐름](../architecture/user-signup-authentication-flow.md): 이메일·Google 인증, 동의·프로필 상태, DB 트랜잭션과 웹·모바일 경계
- [Domain-Driven Design 원칙](../architecture/domain-driven-design.md): 전략·전술 모델링, Ubiquitous Language, Bounded Context와 Aggregate 원칙
- [헥사고날 아키텍처 원칙](../architecture/hexagonal-architecture.md): inside/outside 경계, Port·Adapter와 기술 격리 원칙
- [DDD + 헥사고날 아키텍처 적용 기준](../architecture/ddd-hexagonal-architecture.md): 두 원칙을 현재 모듈러 모놀리스에 적용하는 패키지·계약 규약과 허용 범위
- [Bounded Context Map](../architecture/bounded-context-map.md): Domain Vision, Core Domain, 모델 경계, 현재 접점과 번역 상태
