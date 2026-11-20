# 운영 Resend REST API 전환 절차서

- Status: Active
- Audience: Engineers
- Source of Truth: Yes
- Last Reviewed: 2026-11-14

## 배포 전 준비

1. Resend에 서비스 발신 도메인을 등록합니다. DNS에 대시보드가 제시한 SPF·DKIM 레코드를 추가하고 도메인 상태가 `Verified`로 바뀌는지 확인합니다. [Resend 발신 도메인 문서](https://resend.com/docs/dashboard/domains/introduction)
2. 운영 담당자와 서비스 발신 주소 및 피드백을 받을 사서함을 확인합니다. 발신 주소의 도메인은 Resend에서 인증한 도메인과 일치해야 합니다.
3. 인증된 도메인으로 발송할 수 있는 API 키를 비밀 관리 수단에 보관하고 운영 앱 프로세스의 환경변수로 다음 값을 설정합니다. `application-prod.yml`은 `OUTBOUNDEMAIL_APIKEY` 환경변수를 `outbound-email.api-key`에 연결합니다.

   | 변수 | 설정할 값 |
   | --- | --- |
   | `OUTBOUNDEMAIL_APIKEY` | Resend 발송용 API 키 |
   | `MAIL_FROM_ADDRESS` | 인증된 도메인의 서비스 발신 주소 |
   | `FEEDBACK_RECIPIENT` | 피드백을 받을 운영 사서함 주소 |

   세 값 중 하나라도 비어 있으면 운영 앱 기동이 실패합니다.

4. 운영 호스트에서 `https://api.resend.com/emails`로 나가는 HTTPS 연결을 허용합니다. 앱은 `OUTBOUNDEMAIL_APIKEY`를 Bearer 토큰으로 전달해 Resend Emails API에 POST합니다. SMTP 포트와 STARTTLS 설정은 사용하지 않습니다. [Resend Send Email API](https://resend.com/docs/api-reference/emails/send-email)
5. 전환 전에 이전 배포 산출물과 이전 런타임 설정을 복구할 수 있는지 확인합니다. 현재 Resend 계정의 시간당 발송 추이와 일·월 사용량을 확인하고 기존 피크 발송량을 수용할 수 있는지 점검합니다. [Resend 사용량과 요금제](https://resend.com/pricing)

## 전환 확인

1. 새 빌드를 운영 프로필로 배포하고 애플리케이션의 기동과 헬스 체크를 확인합니다.
2. 허용된 도메인의 관리 가능한 테스트 주소로 회원가입 인증을 요청합니다. Resend의 Emails 화면과 수신함에서 발송 결과를 확인합니다. 수신한 코드로 인증하고 닉네임 예약 후 가입까지 완료합니다.
3. 테스트 계정으로 피드백을 제출합니다. 운영 피드백 사서함에서 본문, 첨부 파일명과 내용이 일치하는지 확인합니다.
4. 별도 관리 계정으로 비밀번호 재설정 메일을 받은 뒤 인증이 가능한지 확인합니다. 관리자 안내 메일 발송을 사용하는 환경은 지정된 테스트 수신함으로 안내 메일도 확인합니다.

## 장애 확인 및 롤백

- Resend의 Emails 화면에서 전달, 지연, 반송 상태를 확인하고 앱 로그의 안전한 provider message ID와 대조합니다. API 요청 타임아웃이 발생하면 실제 발송 여부가 불확실하므로 재시도 전에 수신함과 Resend 기록을 확인합니다. [Resend Send Email API](https://resend.com/docs/api-reference/emails/send-email)
- 가입 인증 API의 `EMAIL_SEND_FAILED` 응답 또는 문의 로그의 `event=inquiry_notification_failed`가 늘면 키 권한, 도메인 검증 상태, HTTPS 연결, Resend 발송 제한을 확인합니다. 문의 메일 발송이 실패해도 접수된 내용과 첨부 참조는 DB에서 확인할 수 있습니다.
- 전환 직후 발송이 지속적으로 실패하면 직전 배포 산출물과 이전 운영 런타임 설정을 함께 복원합니다. 앱 기동과 메일 수신을 확인한 뒤 Resend 설정을 수정하고 전환 점검을 다시 수행합니다.
