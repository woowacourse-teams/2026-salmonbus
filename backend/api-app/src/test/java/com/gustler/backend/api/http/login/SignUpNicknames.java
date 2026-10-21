package com.gustler.backend.api.http.login;

/** 가입할 때 회원 모듈이 만드는 닉네임 형식. 형용사, 한글 2~4자 정류장 이름, 0으로 시작하지 않는 두세 자리 숫자를 잇는다. */
final class SignUpNicknames {
    static final String PATTERN = "^(졸린|수다스러운|용감한|느긋한|반짝이는|신나는|엉뚱한|다정한"
        + "|배고픈|명랑한|부지런한|설레는|재빠른|포근한|궁금한|씩씩한)[가-힣]{2,4}[1-9][0-9]{1,2}$";

    private SignUpNicknames() {
    }
}
