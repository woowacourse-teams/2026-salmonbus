package com.gustler.devtools;

import java.util.List;

/** 검토한 초기화 코드의 정방향 전환만 허용한다. 초기 데이터 변경은 별도 검사한다. */
final class CompatiblePreparation {
    static final String PREVIOUS = "1760d3f777e91af09ca577ebabb6faa0ab577f79aef92ef2bb8be27750f3fa56";
    static final String COLLECTED_DATA = "52b4d7117c1db439b8e90468eb334fc063f20a12387d4a72495ed936a45a508d";

    // SAL-222: 명시적 초기화 대상에 최소 평가 테이블을 포함한다. 기존 데이터와 번들은 유지한다.
    static final String EVALUATION_DATA = "37b4cace323ff5054a45266d740b05ba938b8c62aaf995c011256c710c190dc3";

    // SAL-223: 특징 계산 코드의 컨벤션을 정리했다. 초기화 코드와 모델 번들의 내용은 그대로다.
    static final String NEXT = "fb6c6841a0236e558219cb431f2fb4cdb7dbd28e3423efef31206fdbe10fd649";

    // SAL-359: 명시적 초기화에 레스 정산 테이블 세 개를 포함한다. 데이터/모델 파일은 바꾸지 않는다.
    static final String LES_EVALUATION_DATA = "17a98f6b1ac235c9d77dc59823cd55974dcdc5c045a879c77e81f6b074df7616";

    private static final List<String> REVIEWED_ORDER = List.of(
        PREVIOUS, COLLECTED_DATA, EVALUATION_DATA, NEXT, LES_EVALUATION_DATA);

    static boolean permits(RevisionStore.Revision previous, RevisionStore.Revision next) {
        String from = previous.files().get("tools/data-contract.sha256");
        String to = next.files().get("tools/data-contract.sha256");
        if (from == null || to == null) {
            return false;
        }
        final int previousIndex = REVIEWED_ORDER.indexOf(from);
        final int nextIndex = REVIEWED_ORDER.indexOf(to);
        return previousIndex >= 0 && nextIndex > previousIndex;
    }
}
