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

CREATE TABLE member_identity (
    provider    varchar(20)  NOT NULL,
    subject     varchar(255) NOT NULL,
    member_id   bigint       NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT pk_member_identity PRIMARY KEY (provider, subject),
    CONSTRAINT fk_member_identity_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_member_identity_provider CHECK (provider IN ('KAKAO', 'APPLE'))
);
CREATE INDEX ix_member_identity_member ON member_identity (member_id);

-- 아래 두 표는 spring-session-jdbc 4.1.0 의 schema-postgresql.sql 원문이다. 라이브러리가 표 이름과 열을 정하므로 바꾸지 않는다.
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
