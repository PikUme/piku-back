# API Page Responses

- Status: Active
- Audience: Engineers
- Source of Truth: Yes
- Last Reviewed: 2026-09-07

## 목적과 적용 범위

이 문서는 정상 페이지 응답의 표현과 책임을 정의한다. 페이지 번호 기반 조회의 HTTP 응답은 백엔드가 소유하는 명시적 Web DTO로 반환하며 Spring Data의 내부 페이지 객체를 직접 직렬화하지 않는다.

공통 `OffsetPageResponse`는 원댓글, 답글, 친구 목록, 받은 친구 요청과 사용자 검색에 적용한다. 오류는 별도의 API 오류 응답 표준에 따라 RFC 9457 Problem Details를 유지한다. 조회 결과가 비어 있어도 성공한 빈 페이지를 반환하며 204나 404로 바꾸지 않는다.

## 책임과 변환 경계

Web Adapter는 요청 페이지와 정렬을 해석하여 기술 중립적인 `PageQuery`를 Application에 전달한다. Persistence Adapter는 저장소 페이지를 `PageResult`로 바꾸고 Application은 이를 조회 결과로 사용한다. 각 Context의 Web Adapter는 항목의 익명성, 권한, 프로필과 이미지 URL을 기존 항목 DTO로 표현한다.

Global Web 경계의 `OffsetPageResponseMapper`는 변환된 항목과 페이지 메타데이터만 조립한다. 페이지 번호와 크기는 조회 결과에서, 정렬 상태는 적용된 요청에서 얻는다. Global은 특정 Context의 모델이나 비즈니스 규칙을 해석하지 않는다.

응답 DTO는 Spring Data의 `Page`를 구현하거나 상속하지 않으며 내부에도 `Page`, `Pageable`, `Sort` 객체를 보관하지 않는다. Domain과 Application에는 Web DTO나 Jackson Annotation을 추가하지 않는다. 요청 바인딩의 `Pageable`과 Persistence 내부의 Spring Data 페이지 사용은 허용한다.

## 정상 응답 계약

최상위에는 아래 11개 필드를 유지한다. 이름, 위치, 타입과 의미가 계약이며 JSON 필드 출력 순서는 계약이 아니다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| content | 배열 | 기존 항목 DTO와 순서, nullable 값, 권한 표현 |
| number | 정수 | 0부터 시작하는 페이지 번호 |
| size | 정수 | 적용된 페이지 크기 |
| totalElements | 정수 | 호환 보정을 적용한 전체 항목 수 |
| totalPages | 정수 | 전체 항목 수와 페이지 크기에 따른 전체 페이지 수 |
| numberOfElements | 정수 | 현재 페이지의 실제 항목 수 |
| empty | 불리언 | 현재 페이지가 비었는지 여부 |
| first | 불리언 | 첫 페이지인지 여부 |
| last | 불리언 | 다음 페이지가 없는지 여부 |
| pageable | 객체 | 페이지 위치와 정렬 메타데이터 |
| sort | 객체 | 적용된 정렬의 존재 여부 |

`pageable`은 `pageNumber`, `pageSize`, `offset`, `paged`, `unpaged`, `sort`를 담는다. 현재 적용 API는 페이지 요청을 사용하므로 `paged`는 참, `unpaged`는 거짓이다. `offset`은 큰 페이지 번호에서도 정수 곱셈이 넘치지 않도록 64비트 값으로 계산한다.

최상위와 `pageable` 내부의 두 `sort`는 동일한 상태를 나타낸다. 각 객체에는 불리언 `empty`, `sorted`, `unsorted`가 있으며 정렬이 없으면 각각 참, 거짓, 참이다. 기본 정렬이 없는 API에 새 정렬을 추가하지 않는다.

## 경계 조건과 호환 보정

- 전체 결과가 없는 첫 페이지는 빈 배열, 전체 개수 0, 전체 페이지 수 0, 실제 항목 수 0을 반환한다. `first`, `last`, `empty`는 참이며 `size`는 적용된 요청 크기를 유지한다.
- 범위를 벗어난 빈 페이지도 요청된 페이지 번호와 보고된 전체 개수를 유지한다. `last`와 `empty`는 참이며 첫 페이지가 아니면 `first`는 거짓이다.
- 중간 페이지는 다음 페이지가 있으면 `last`가 거짓이다. 클라이언트는 최상위 `last`를 기준으로 추가 조회를 종료한다.
- 내용이 비어 있지 않고 페이지의 요청 끝 위치가 보고된 전체 개수를 초과하면, 기존 `PageImpl`의 마지막 구간 보정과 같이 `offset`과 실제 항목 수를 합한 값을 전체 개수로 사용한다. 빈 페이지에는 이 보정을 적용하지 않는다.
- 예를 들어 페이지 번호 1, 크기 2, 보고된 전체 개수 3에 항목이 2개이면 응답 전체 개수는 4이다. 이 보정은 해당 HTTP 호환 응답의 책임이며 Application 공통 값이나 알림 응답의 계산을 바꾸지 않는다.
- 항목 표시 변환으로 페이지 항목을 추가·삭제하거나 순서를 바꾸지 않는다.

## 요청 기본값과 기존 계약

| API | 기본 페이지 크기 | 기본 정렬 |
| --- | --- | --- |
| GET /api/comments | 10 | createdAt 내림차순 |
| GET /api/comments/{parentCommentId}/replies | 10 | createdAt 오름차순 |
| GET /api/relation | 10 | userId1 내림차순 |
| GET /api/relation/requests | 10 | 미지정 |
| GET /api/search | 20 | 미지정 |

기본 페이지 번호는 0이며 공통 최대 페이지 크기는 100이다. 명시적인 페이지, 크기와 정렬 파라미터의 바인딩을 유지한다. 댓글의 일기 식별자와 검색의 검색어 요구사항도 유지한다.

기존 프런트엔드는 `content`, 최상위 `last`와 `totalElements`를 소비한다. 메타데이터를 `page` 하위로 옮기거나 소비 빈도가 낮다는 이유로 호환 필드를 제거하지 않는다. 전역 `VIA_DTO` 적용 또는 다른 페이지 표현으로의 전환은 별도의 소비자 마이그레이션 계약을 필요로 한다.

## 별도 페이지 표현

알림의 `NotificationPageResponse`는 기존의 명시적 페이지 응답과 정렬 정책을 유지한다. 피드의 `FeedCursorPageResponse`와 갤러리의 `DiaryGalleryPageResponse`는 `items`, `nextCursor`, `hasNext`를 사용하는 독립된 커서 계약이다. 이들을 공통 번호 기반 응답에 통합하지 않는다.

## 검증과 운영 적용

계약 테스트는 최상위·중첩 필드, 항목 표현과 경계 조건을 검증한다. Spring Data 페이지 모듈이 적용된 실제 MVC 직렬화에서도 응답을 검증하고, 직접 페이지 직렬화의 양성 대조군으로 경고 감지가 동작함을 확인한다. 경고가 모듈마다 한 번만 출력되는 상태를 격리하여 경고 부재가 거짓 성공으로 판정되지 않게 한다.

생성된 OpenAPI는 동일한 페이지 외형과 구체적인 항목 타입을 설명해야 한다. 댓글의 nullable 작성자 정보와 `canReply`, `canEdit`, `canDelete`도 보존한다. 아키텍처 검증은 Controller 반환값과 응답 내부의 Spring Data 페이지 노출을 막되 요청과 저장소의 허용된 타입 사용은 제한하지 않는다.

변경은 백엔드 응답 조립에 한정하며 데이터 마이그레이션이나 프런트엔드 동시 배포를 요구하지 않는다. 테스트 환경과 배포 후에는 새 인스턴스의 첫 요청부터 다섯 API의 응답, 페이지 로딩 종료, 전체 개수 표시와 경고를 확인한다. 경고가 남으면 배포 버전과 인스턴스를 대조해 이전 버전 또는 남은 직접 직렬화 경로를 구분한다.

호환 문제가 발생하면 직전 백엔드 버전으로 롤백한다. 배포 후 관찰과 실제 클라이언트 동작은 로컬 계약 테스트와 별도의 운영 검증 증거로 기록한다.
