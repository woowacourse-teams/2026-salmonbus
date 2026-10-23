-- 회원은 제공자 계정(member_identity)으로만 찾는다. 밖으로는 public_id 만 내보낸다.
CREATE TABLE member (
    id             bigint      GENERATED ALWAYS AS IDENTITY,
    public_id      uuid        NOT NULL,
    nickname       varchar(10) NOT NULL,
    internal       boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL,
    last_login_at  timestamptz NOT NULL,
    CONSTRAINT pk_member PRIMARY KEY (id),
    CONSTRAINT ux_member_public_id UNIQUE (public_id),
    -- 앱이 공백 제거·NFC 정규화를 마친 값만 들어온다. 한글은 완성형만 허용한다. 금칙어는 앱이 검사한다.
    CONSTRAINT ck_member_nickname CHECK (nickname ~ '^[가-힣A-Za-z0-9]{2,10}$')
);
-- 영문 대소문자만 다른 닉네임도 같은 닉네임으로 본다.
CREATE UNIQUE INDEX ux_member_nickname ON member (lower(nickname));

-- 회원 한 명은 제공자 계정 하나로만 로그인한다.
CREATE TABLE member_identity (
    provider    varchar(20)  NOT NULL,
    subject     varchar(255) NOT NULL,
    member_id   bigint       NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_member_identity PRIMARY KEY (provider, subject),
    CONSTRAINT ux_member_identity_member UNIQUE (member_id),
    CONSTRAINT fk_member_identity_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_member_identity_provider CHECK (provider IN ('KAKAO', 'APPLE'))
);

-- 로그인 계통(family)마다 한 행이다. 토큰은 저장하지 않고 서버 비밀로 서명해 (family_id, generation) 에서 다시 계산한다.
-- generation 은 지금 유효한 세대이고, rotated_at 은 그 세대를 발급한 시각이다.
-- started_at 은 계통을 시작한 로그인 시각이다. 갱신해도 expires_at 은 started_at 뒤 180일을 넘지 않는다.
CREATE TABLE refresh_token (
    family_id   uuid        NOT NULL,
    member_id   bigint      NOT NULL,
    generation  integer     NOT NULL,
    started_at  timestamptz NOT NULL,
    rotated_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (family_id),
    CONSTRAINT fk_refresh_token_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_token_generation CHECK (generation >= 0)
);
CREATE INDEX ix_refresh_token_member ON refresh_token (member_id);
-- 정리 작업이 만료된 계통을 찾는다.
CREATE INDEX ix_refresh_token_expires ON refresh_token (expires_at);

-- 탈퇴한 회원의 제공자 식별자를 연결 해제가 끝날 때까지만 보관한다. 해제에 성공하면 행을 지운다.
-- 회원은 이미 지워졌으므로 member 를 참조하지 않는다. GAVE_UP 은 더 시도하지 않고 사람이 확인할 요청이다.
CREATE TABLE provider_unlink (
    id               bigint       GENERATED ALWAYS AS IDENTITY,
    provider         varchar(20)  NOT NULL,
    subject          varchar(255) NOT NULL,
    status           varchar(20)  NOT NULL,
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    last_error       varchar(200),
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_provider_unlink PRIMARY KEY (id),
    CONSTRAINT ck_provider_unlink_provider CHECK (provider IN ('KAKAO', 'APPLE')),
    CONSTRAINT ck_provider_unlink_status CHECK (status IN ('PENDING', 'GAVE_UP')),
    CONSTRAINT ck_provider_unlink_attempts CHECK (attempts >= 0)
);
-- 주기 작업이 지금 다시 시도할 요청을 찾는다.
CREATE INDEX ix_provider_unlink_due ON provider_unlink (next_attempt_at) WHERE status = 'PENDING';

-- 아래 두 테이블은 spring-session-jdbc 4.1.0 의 schema-postgresql.sql 원문이다. 라이브러리가 테이블 이름과 열을 정하므로 바꾸지 않는다.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
