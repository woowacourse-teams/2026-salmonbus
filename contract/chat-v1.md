# 노선 채팅 계약 v1

프론트와 서버가 함께 지키는 노선 채팅 계약이다. 채팅방은 현재 노선마다 하나씩 있고, 서버는 현재 노선 목록에 있는 노선이면 채팅방을 연다.

요청·응답·프레임의 모양은 [`examples/chat-v1.json`](examples/chat-v1.json)이 원본이다. 이 문서는 규칙만 적는다. 서버 시험과 프론트 시험이 모두 이 예시 파일을 읽으므로, 모양을 바꾸면 예시 파일과 이 문서를 같은 PR에서 고친다.

## 버전

- 모든 프레임은 `v: 1`을 싣는다.
- 필드를 더하는 것은 하위 호환이다. 받는 쪽은 모르는 필드를 무시한다.
- 필드를 빼거나 뜻을 바꾸면 v2로 올린다.

## 켜짐 확인

`GET /api/chat/rooms/{routeId}`, 예시 `availability`

| 상황 | 응답 |
| --- | --- |
| 채팅이 켜져 있고 지원 노선 | `200`, `Cache-Control: no-store`, 본문 `routeId`·`displayName`·`historySize`·`maxBodyCodePoints` |
| 채팅이 꺼져 있거나 지원하지 않는 노선 | `404`, `Cache-Control: no-store` |

- 이 요청은 MongoDB를 읽지 않는다.
- 서버는 403을 쓰지 않는다. CloudFront가 403을 `index.html` 200으로 바꾸므로, 프론트는 200이어도 본문이 계약 모양이 아니면 채팅을 숨긴다.
- 프론트는 200일 때만 알약과 채팅 청크를 그리고, 곧바로 WebSocket을 연다. 채팅을 열지 않은 동안에도 새 메시지를 받아 알약에 안 읽은 수와 마지막 메시지를 보여 준다.

## WebSocket

`GET /api/chat/rooms/{routeId}/stream`

- JSON 텍스트 프레임만 쓴다. 들어오는 프레임은 1,024바이트까지다. 넘으면 서버가 연결을 닫는다(1009 또는 치명 오류 `INVALID_FRAME`).
- 연결한 뒤 5초 안에 `session.start`가 와야 한다. 안 오면 치명 오류 `HELLO_REQUIRED`다.
- 서버는 25초마다 프로토콜 ping을 보내고, 70초 동안 아무것도 오가지 않으면 4500으로 닫는다. 브라우저는 pong을 알아서 보낸다.

### 흐름

1. 클라이언트 `session.start`
2. 서버 `session.ready`
3. 서버 `history.batch` 0개 이상. 최근 50개를 오래된 순으로 한 프레임에 10개까지 싣는다.
4. 서버 `history.end`
5. 클라이언트 `message.send` → 서버가 저장한 뒤 보낸 사람에게 `message.ack`, 같은 방의 모든 세션(보낸 사람 포함)에 `message.created`

### 클라이언트 프레임

| type | 필드 | 규칙 |
| --- | --- | --- |
| `session.start` | `clientSessionId` | UUID v4. 탭 수명 `sessionStorage`에 둔다. 다시 연결할 때도 같은 값을 보낸다 |
| `message.send` | `clientMessageId`, `body` | `clientMessageId`는 UUID v4. 다시 보낼 때는 같은 값을 쓴다 |

### 서버 프레임

| type | 필드 | 규칙 |
| --- | --- | --- |
| `session.ready` | `authorId`, `nickname`, `maxBodyCodePoints` | 아래 "익명 식별" |
| `history.batch` | `messages[]` | 1~10개, 오래된 순 |
| `history.end` | 없음 | 이력 전송 끝 |
| `message.ack` | `clientMessageId`, `duplicate`, `message` | 같은 요청을 다시 받았으면 `duplicate: true`이고 저장된 메시지를 그대로 돌려준다. 이때 `message.created`는 다시 보내지 않는다 |
| `message.created` | `message` | 저장된 새 메시지 |
| `error` | `requestId`, `code`, `message`, `retryAfterMs`, `fatal` | 아래 "오류" |

`message`는 `id`, `authorId`, `nickname`, `body`, `createdAt`이다.

- `id`는 서버가 정한 문자열이다. 클라이언트는 모양을 가정하지 않는다.
- `createdAt`은 ISO-8601 UTC(`Z`)다. 소수부는 없을 수도 있고 있을 수도 있다(`…:02Z`, `…:41.503Z`).

## 보내기 제한

- `body`는 앞뒤 공백을 뗀 뒤 1~200 코드포인트다. 넘으면 `INVALID_BODY`다.
- 연결 하나에서 1초에 1건이다. 몰아 보내기(버스트)는 없다. 넘으면 `RATE_LIMITED`이고 `retryAfterMs`는 1000이다.
- 서버 전체와 IP마다 연결 수 상한이 있다. 넘으면 서버가 1013으로 닫는다.

## 오류

`error` 프레임에는 다섯 필드가 항상 있다. `requestId`와 `retryAfterMs`는 값이 없으면 `null`이다.

- `requestId`: `message.send`에 대한 오류면 그 `clientMessageId`, 그 밖에는 `null`
- `retryAfterMs`: `RATE_LIMITED`일 때만 숫자, 그 밖에는 `null`
- `message`: 서버 문구. 프론트는 `code`로 자기 문구를 고르고, 모르는 코드일 때만 이 값을 쓴다
- `fatal: true`면 서버가 곧 1008로 닫는다

| code | 언제 | fatal | requestId |
| --- | --- | --- | --- |
| `INVALID_FRAME` | JSON이 아니거나 모양이 틀림, 1,024바이트 초과, 이미 시작한 세션에 `session.start` | 세션 시작 전이거나 크기 초과면 true, 그 밖에는 false | null |
| `HELLO_REQUIRED` | `session.start` 전에 다른 프레임, 5초 안에 `session.start` 없음 | true | 보낸 프레임이 `message.send`면 그 ID, 아니면 null |
| `UNSUPPORTED_PROTOCOL` | `v`가 1이 아님 | true | 위와 같음 |
| `INVALID_BODY` | 본문이 비었거나 200 코드포인트 초과 | false | `clientMessageId` |
| `RATE_LIMITED` | 1초에 1건을 넘음 | false | `clientMessageId` |
| `ID_CONFLICT` | 같은 `clientMessageId`로 다른 내용 | false | `clientMessageId`. 클라이언트는 새 ID로 다시 보낸다 |
| `CHAT_UNAVAILABLE` | 저장소에 닿지 못함 | false. `session.start` 중이면 서버가 이어서 1011로 닫는다 | 보내기 중이면 `clientMessageId`, 아니면 null |

## 종료 코드와 다시 잇기

| 코드 | 언제 | 클라이언트 |
| --- | --- | --- |
| 1003 | 지원하지 않는 노선 | 다시 잇지 않는다. "연결하지 못했어요" |
| 1008 | 치명 오류(`fatal: true`) 뒤 | 다시 잇지 않는다. "연결하지 못했어요" |
| 1013 | 연결 수 상한 초과 | 간격을 늘려 가며 다시 잇는다 |
| 1001 | 서버 종료·재시작 | 다시 잇는다 |
| 1011 | 서버 오류(보내기 실패 등), 입장 때 저장소에 닿지 못함 | 다시 잇는다 |
| 4500 | 70초 비활성 | 다시 잇는다 |
| 그 밖(1006 등) | 네트워크 끊김 | 다시 잇는다 |

- 다시 잇기는 간격을 늘려 가며 하고, 횟수에 상한을 둔다. 사용자가 "다시 연결"을 누르면 처음부터 다시 한다.
- 다시 이으면 `session.start`부터 다시 한다. 서버는 최근 이력을 다시 보내고, 클라이언트는 `id`로 합친다.

## 멱등

- 서버는 `{roomId, clientMessageId}` 고유 인덱스로 같은 요청을 한 번만 저장한다.
- 같은 ID·같은 작성자·같은 본문이면 `message.ack`의 `duplicate: true`, 내용이 다르면 `ID_CONFLICT`다.
- 저장이 끝난 뒤에만 `message.ack`와 `message.created`를 보낸다.

## 익명 식별

- 서버는 `routeId`와 `clientSessionId`의 SHA-256으로 `authorId`(22자 base64url)와 닉네임을 정한다. 같은 탭은 다시 연결해도 같은 이름이고, 노선이 다르면 이름도 다르다.
- 닉네임은 "재밌는 형용사 + 그 노선의 정류장 이름"이다. 정류장 이름은 괄호, 마침표 뒤, 끝의 "앞"을 뗀 짧은 이름을 쓴다. 예: `졸린 범계역`.
- 이 값은 표시용이다. 인증이나 사람 수의 근거로 쓰지 않는다.
- 원래의 `clientSessionId`와 IP, 메시지 본문은 로그에 남기지 않는다. DB에는 `clientSessionId`와 IP를 저장하지 않는다.

## 저장

- DB `salmonbus_chat`, 컬렉션 `messages`
- 인덱스
  - `room_client_message_unique`: `{roomId: 1, clientMessageId: 1}`, 고유
  - `room_recent`: `{roomId: 1, createdAt: 1, _id: 1}`
- 보존 기한은 없다. TTL 인덱스를 두지 않는다.
- 컬렉션과 인덱스는 `backend/deploy/chat/init-chat-db.js`가 만든다. 앱은 인덱스를 만들지 않고, 앱 계정은 `readWrite`만 쓴다.
- 과거 이력 페이지 넘기기는 v1에 없다. 최근 50개만 준다.
