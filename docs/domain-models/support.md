# Support 도메인 모델

- Status: Active
- Audience: Engineers
- Source of Truth: Yes
- Last Reviewed: 2026-07-23

## 도메인 개요

Support는 인증된 사용자의 문의 내용과 선택적 첨부 저장 참조를 접수하고 운영 알림을 요청하는 Supporting Context다.

### 목적

- 문의 제출자가 현재 사용자로 존재하는지 확인한다.
- 문의 내용과 선택적 첨부 이미지 참조를 기록한다.
- 첨부가 있으면 Support 소유 Object Key로 저장한다.
- 운영자가 문의를 확인할 수 있도록 메일 전달을 시도한다.

## Inquiry

_Aggregate Root_

### 속성

- `id`: 문의 식별자
- `userId`: 문의 제출 사용자 식별자
- `content`: 문의 내용
- `attachmentReference`: 선택적 첨부 이미지의 Object Storage 참조
- `createdAt`, `updatedAt`: 문의 기록 시각과 수정 시각

`attachmentReference`는 Domain에서 URL로 해석하지 않는다. 기존 DB 호환을 위해 물리 열 이름은 `image_url`을 유지하지만 모델 의미는 저장소 참조다.

### 생성과 저장 규칙

- 문의 제출 사용자 식별자는 null 또는 공백일 수 없다.
- 문의 내용은 null일 수 없고 현재 DB 저장 한도인 1000자를 넘을 수 없다.
- 현재 제품 계약은 공백 내용 자체를 별도로 거부하지 않는다.
- 첨부는 선택이며 문의 하나에 최대 하나의 저장 참조를 기록한다.
- 첨부의 원본 파일 바이트는 값 경계를 넘을 때 방어 복사한다.

## 현재 문의 접수 흐름

현재 접수 흐름은 다음 순서를 유지한다.

1. User 공개 Application 계약으로 제출자 존재를 확인한다.
2. 비어 있지 않은 첨부가 있으면 Object Storage에 저장한다.
3. 첨부 원본을 포함해 운영 메일 전달을 동기로 시도한다.
4. 메일 전달 성공 여부와 관계없이 문의를 DB에 기록한다.

### 실패 의미

- 제출자가 없으면 문의를 접수하지 않는다.
- 첨부 저장이 실패하면 메일과 문의 기록을 수행하지 않는다.
- 운영 메일 실패는 현재 문의 기록을 막지 않는다.
- 문의 기록 실패 시 앞서 저장된 첨부나 발송된 메일을 현재 흐름에서 보상하지 않는다.

문의 DB 기록을 우선하고 커밋 이후 메일을 전달하는 변경, 고아 첨부 삭제와 메일 재시도는 별도 기능·전달 강화 계획에서 결정한다.

## 첨부 저장 규칙

- Object Key는 `inquiry/{날짜}/{사용자 구분}_{UUID}.{확장자}` 형식을 사용한다.
- 정상 UUID 사용자는 기존과 같이 앞 8자를 사용자 구분으로 사용한다.
- 짧은 사용자 식별자는 길이 오류 없이 안전한 문자로 정규화한다.
- 파일 확장자는 영문과 숫자로만 구성된 최대 10자 값만 보존한다.
- Object Storage Provider, 버킷과 SDK 타입은 Support Application과 Domain에 노출하지 않는다.

## 경계와 소유권

- Support는 문의 내용, 제출자, 첨부 참조, 첨부 Object Key와 운영 메일 템플릿을 소유한다.
- User는 사용자 계정과 존재 여부의 원천 모델을 소유하며 Support에는 공개 참조 계약만 제공한다.
- Global은 바이트 저장을 위한 중립 Object Storage와 공급자 중립 outbound email 전송 능력을 제공한다.
- Multipart 변환과 HTTP 표현은 Support Web Adapter가 소유한다.
- 문의 템플릿과 메시지 구성은 Support Email Adapter가 소유하고, 공급자별 HTTP 전송과 요청 형식 변환은 Global 메일 어댑터가 소유한다.
- User Auth의 메일 템플릿과 Support 문의 템플릿을 공유하지 않는다.

## API 오류 표현

- 존재하지 않는 제출자, 유효하지 않은 문의와 첨부 저장 실패는 기술 중립 Support 오류로 표현한다.
- Web Adapter는 Support 오류를 RFC 9457 Problem Details로 변환한다.
- Provider 메시지, 내부 메일 주소와 Object Key는 오류 detail에 포함하지 않는다.
- 현재 성공 계약은 `POST /api/inquiry`의 201 상태와 빈 본문이다.
