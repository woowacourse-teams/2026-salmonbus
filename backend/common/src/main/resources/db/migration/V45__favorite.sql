CREATE TABLE favorite_group (
    id         uuid    NOT NULL,
    member_id  bigint  NOT NULL,
    name       text    NOT NULL,
    position   smallint NOT NULL,
    CONSTRAINT pk_favorite_group PRIMARY KEY (id),
    CONSTRAINT fk_favorite_group_member FOREIGN KEY (member_id)
        REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ux_favorite_group_member_name UNIQUE (member_id, name),
    CONSTRAINT ux_favorite_group_member_position UNIQUE (member_id, position),
    CONSTRAINT ck_favorite_group_name CHECK (name <> '' AND name = btrim(name)),
    CONSTRAINT ck_favorite_group_position CHECK (position BETWEEN 0 AND 2)
);

CREATE TABLE favorite (
    id                 uuid       NOT NULL,
    favorite_group_id  uuid       NOT NULL,
    route_id           varchar(9) NOT NULL,
    stop_id            varchar(9) NOT NULL,
    direction          varchar(4) NOT NULL,
    position           smallint   NOT NULL,
    CONSTRAINT pk_favorite PRIMARY KEY (id),
    CONSTRAINT fk_favorite_group FOREIGN KEY (favorite_group_id)
        REFERENCES favorite_group (id) ON DELETE CASCADE,
    CONSTRAINT ux_favorite_group_target UNIQUE (favorite_group_id, route_id, stop_id, direction),
    CONSTRAINT ux_favorite_group_position UNIQUE (favorite_group_id, position),
    CONSTRAINT ck_favorite_route_id CHECK (route_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_favorite_stop_id CHECK (stop_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_favorite_direction CHECK (direction IN ('UP', 'DOWN')),
    CONSTRAINT ck_favorite_position CHECK (position BETWEEN 0 AND 1)
);

INSERT INTO favorite_group (id, member_id, name, position)
SELECT gen_random_uuid(), id, '즐겨찾기', 0
FROM member;
