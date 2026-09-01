-- 어떤 계수 묶음이 지금 도는가.
-- 상태 전이 규칙(STAGED → ACTIVE → RETIRED)은 승격 로직과 함께 와야 뜻이 있어서 SAL-93 이 조인다.
--
-- 아직 못 정한 것
--   deployment_key · release_id · model_key · model_version 넷이 서로 무엇이 다른지 설명되지 않는다.
--   문서에서 그대로 가져왔고, 셋 중 둘은 지울 수 있어 보인다. SAL-93 이 배포 절차를 짜면서 정한다.

CREATE TABLE model_deployment (
    id                         bigint      GENERATED ALWAYS AS IDENTITY,
    deployment_key             uuid        NOT NULL,
    release_id                 varchar(80) NOT NULL, -- 응답 model.releaseId
    model_key                  varchar(40) NOT NULL,
    model_version              varchar(60) NOT NULL, -- 학습된 계수 묶음. 코드의 git 식별자
    bundle_digest              char(64)    NOT NULL,
    prediction_target_version  varchar(40) NOT NULL,
    calculation_version        varchar(40) NOT NULL, -- 입력값을 만드는 규칙. 모델 버전과 다른 층이다
    supported_scope_digest     char(64)    NOT NULL, -- state 와 함께 응답 route.status 를 판정한다.
                                                     -- 이 노선 판본을 담으면 FORECAST_READY,
                                                     -- 안 담으면 MODEL_OUT_OF_SCOPE 다. 유도할 수 없어 열로 둔다
    data_until                 timestamptz NOT NULL, -- 응답 model.trainedThrough
    state                      varchar(12) NOT NULL, -- STAGED | ACTIVE | RETIRED
    predecessor_deployment_id  bigint,
    activated_at               timestamptz,
    retired_at                 timestamptz,
    CONSTRAINT pk_model_deployment PRIMARY KEY (id),
    CONSTRAINT ux_model_deployment_key UNIQUE (deployment_key),
    CONSTRAINT fk_model_deployment_predecessor FOREIGN KEY (predecessor_deployment_id)
        REFERENCES model_deployment (id)
);

-- 도는 모델은 언제나 하나뿐이다
CREATE UNIQUE INDEX ux_model_deployment_single_active
    ON model_deployment ((state))
    WHERE state = 'ACTIVE';
