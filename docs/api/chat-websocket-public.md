# 노선 채팅 계약 v1

이 문서는 노선 채팅의 **WebSocket 메시지 계약**이다. HTTP 요청·응답 명세와 구분한다. 예시 JSON은 `/api/docs/chat/examples`에서 볼 수 있다. 채팅 기능이 꺼져 있거나 지원하지 않는 노선이면 연결할 수 없다.

## 연결

1. `GET /api/chat/rooms/{routeId}`가 200이면 채팅방을 사용할 수 있다. 응답은 `routeId`, `displayName`, `historySize`, `maxBodyCodePoints`를 포함하고 `Cache-Control: no-store`다. 채팅이 꺼져 있거나 지원하지 않는 노선은 본문 없는 404다.
2. `GET /api/chat/rooms/{routeId}/stream`으로 WebSocket을 연다. JSON 텍스트 프레임만 사용하며 각 프레임에 `v: 1`을 싣는다.
3. 연결 뒤 5초 안에 클라이언트가 `session.start`를 보낸다. 그 뒤 서버가 `session.ready`, `history.batch` 0개 이상, `history.end` 순서로 보낸다.

프레임 크기 상한은 1,024바이트다. 서버는 25초마다 프로토콜 ping을 보내고, 70초 동안 트래픽이 없으면 연결을 닫는다. 브라우저는 프로토콜 pong을 자동 처리한다.

## 메시지

| 방향 | `type` | 주요 필드와 의미 |
| --- | --- | --- |
| 클라이언트 → 서버 | `session.start` | `clientSessionId`: 탭 수명 동안 재연결해도 같은 UUID v4 |
| 서버 → 클라이언트 | `session.ready` | `authorId`, `nickname`, `maxBodyCodePoints`. 익명 표시용이며 사람 인증의 근거가 아님 |
| 서버 → 클라이언트 | `history.batch` | `messages`: 최근 이력의 오래된 순서, 한 프레임 최대 10개 |
| 서버 → 클라이언트 | `history.end` | 이력 전송 완료 |
| 클라이언트 → 서버 | `message.send` | `clientMessageId`: 재시도할 때도 같은 UUID v4, `body`: 공백 제거 후 1~200 코드포인트 |
| 서버 → 클라이언트 | `message.ack` | `clientMessageId`, `duplicate`, 저장된 `message`. 같은 요청 재전송이면 `duplicate: true` |
| 서버 → 클라이언트 | `message.created` | 방의 모든 세션에 전달되는 새 `message` |
| 서버 → 클라이언트 | `error` | `requestId`, `code`, `message`, `retryAfterMs`, `fatal` |

`message`는 `id`, `authorId`, `nickname`, `body`, `createdAt`을 포함한다. `createdAt`은 UTC ISO-8601이며 소수부는 있을 수도 없을 수도 있다. 최근 이력은 최대 50개다. `message.send`는 연결당 1초에 1건까지다.

## 오류와 재연결

`error.requestId`는 해당 `message.send`의 `clientMessageId` 또는 null이다. `retryAfterMs`는 `RATE_LIMITED`일 때 1000, 그 밖에는 null이다. `fatal: true`면 서버가 연결을 닫는다.

| 오류 코드 | 의미 |
| --- | --- |
| `INVALID_FRAME` | JSON 모양 또는 프레임 크기 오류 |
| `HELLO_REQUIRED` | `session.start`를 보내지 않았거나 5초를 넘김 |
| `UNSUPPORTED_PROTOCOL` | `v`가 1이 아님 |
| `INVALID_BODY` | 메시지 본문이 비었거나 200 코드포인트 초과 |
| `RATE_LIMITED` | 연결당 1초 1건 제한 초과 |
| `ID_CONFLICT` | 같은 `clientMessageId`에 다른 본문을 사용함 |
| `CHAT_UNAVAILABLE` | 메시지 처리에 필요한 자원을 사용할 수 없음 |

지원하지 않는 노선은 1003, 치명 오류는 1008로 닫는다. 연결 수 제한은 1013, 서버 오류는 1011, 비활성 종료는 4500이다. 서버 재시작·네트워크 끊김·1011·1013·4500은 간격을 늘려 재연결한다. 1003·1008은 자동 재연결하지 않는다.

재연결할 때 `session.start`를 다시 보내고 같은 `clientSessionId`를 쓴다. 미확인 `message.send`를 다시 보낼 때는 같은 `clientMessageId`를 사용한다. 서버가 최근 이력을 다시 보내므로 클라이언트는 `message.id`로 중복을 합친다.
