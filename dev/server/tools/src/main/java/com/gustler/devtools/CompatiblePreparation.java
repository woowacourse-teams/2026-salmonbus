package com.gustler.devtools;

import java.util.List;

/** 검토한 초기화 코드 전환만 허용한다. 초기 데이터 변경은 별도 검사한다. */
final class CompatiblePreparation {
    static final String PREVIOUS = "1760d3f777e91af09ca577ebabb6faa0ab577f79aef92ef2bb8be27750f3fa56";
    static final String COLLECTED_DATA = "52b4d7117c1db439b8e90468eb334fc063f20a12387d4a72495ed936a45a508d";

    // SAL-222: 명시적 초기화 대상에 최소 평가 테이블을 포함한다. 기존 데이터와 번들은 유지한다.
    static final String EVALUATION_DATA = "37b4cace323ff5054a45266d740b05ba938b8c62aaf995c011256c710c190dc3";

    // SAL-223: 특징 계산 코드의 컨벤션을 정리했다. 초기화 코드와 모델 번들의 내용은 그대로다.
    static final String NEXT = "fb6c6841a0236e558219cb431f2fb4cdb7dbd28e3423efef31206fdbe10fd649";

    static final String FIXTURE_DIGEST = "bae7cee31d0a289b72dff1e55c72aab7194dce45db1d30c4b01f3a6073ebef46";

    // SAL-359: 노선 지문을 정리한 dev 초기화에 하차 반영 정산 테이블을 포함한다.
    static final String PREVIOUS_ALIGHTING_AWARE_EVALUATION_DATA =
        "17a98f6b1ac235c9d77dc59823cd55974dcdc5c045a879c77e81f6b074df7616";
    static final String ALIGHTING_AWARE_EVALUATION_DATA = "30cf3a383cbd23cc0180d0028697cb54eaad6f04ab024feab8a89ee789e16410";

    // SAL-385: 폐기한 품질 및 재구축 테이블 대신 최종 원본 보관 스키마를 초기화한다.
    static final String ARCHIVE_DATA = "febd4e42fed774cd46d4e3b42861e69bbf7e66bc6baf8b2e53132627ffebbc19";

    static final String ALIGHTING_MODEL_DATA = "0779f0aef421c9a5120c94cf9c3434085e39dfbd38aa2da213a9a0d3b87e8e1e";

    static final String SINGLE_MODEL_DATA = "e8cbb759abd67ff2af54253e3692e7a9370a6b252be69186a12daed2c8460a07";

    private static final List<String> REVIEWED_ORDER = List.of(
        PREVIOUS, COLLECTED_DATA, EVALUATION_DATA, NEXT, PREVIOUS_ALIGHTING_AWARE_EVALUATION_DATA, ALIGHTING_AWARE_EVALUATION_DATA,
        ARCHIVE_DATA, ALIGHTING_MODEL_DATA, SINGLE_MODEL_DATA);

    static boolean permits(RevisionStore.Revision previous, RevisionStore.Revision next) {
        String from = previous.files().get("tools/data-contract.sha256");
        String to = next.files().get("tools/data-contract.sha256");
        if (from == null || to == null) {
            return false;
        }
        if (FIXTURE_DIGEST.equals(from)) {
            return NEXT.equals(to) || ALIGHTING_AWARE_EVALUATION_DATA.equals(to) || ARCHIVE_DATA.equals(to) || ALIGHTING_MODEL_DATA.equals(to);
        }
        if (FIXTURE_DIGEST.equals(to)) {
            return NEXT.equals(from);
        }
        final int previousIndex = REVIEWED_ORDER.indexOf(from);
        final int nextIndex = REVIEWED_ORDER.indexOf(to);
        return previousIndex >= 0 && nextIndex > previousIndex;
    }
}
