-- 정류장 이름 검색용 정규화 키. 영문 소문자·숫자·한글만 남겨 공백·문장부호 차이를 없앤다.
-- 검색 때마다 46,000여 행에 정규식을 돌리지 않도록 저장 생성 열로 둔다. PostgreSQL 18은 생성 열이 기본 VIRTUAL이라 STORED를 꼭 적는다.
-- 인덱스는 두지 않는다. 앞뒤 와일드카드 LIKE는 순차 스캔으로 충분하다.
SET LOCAL lock_timeout = '1s';

CREATE FUNCTION station_search_key(text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN lower(regexp_replace($1, '[^0-9A-Za-z가-힣]', '', 'g'));

-- 정규화 규칙을 바꾸려면 함수만 교체해서는 안 된다. 저장된 값은 다시 계산되지 않으므로
-- name_key 열을 지우고 새 규칙으로 다시 만들어야 한다. 백엔드 StationSearchQuery 의 규칙도 함께 바꾼다.
ALTER TABLE bus_station
    ADD COLUMN name_key text GENERATED ALWAYS AS (station_search_key(name)) STORED;
