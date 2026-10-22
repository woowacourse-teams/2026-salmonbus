package com.gustler.backend.api.http.login;

import com.gustler.backend.member.api.SignInWithSocialAccount;
import com.gustler.backend.support.PostgresTestContainer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 로그인과 카카오·애플을 모두 켠 앱을 실제 톰캣으로 띄운다. 이 표시를 단 테스트는 한 컨텍스트를 함께 쓴다.
 *
 * <p>제공자 주소는 {@link FakeLoginProviders} 가 띄운 테스트용 서버를 가리킨다. 회원 처리 실패를 흉내 내야 하는 테스트가
 * 있어 {@link SignInWithSocialAccount} 는 실제 동작을 그대로 하는 스파이로 둔다. 스파이에 건 동작은 테스트가 끝나면 풀린다.
 *
 * <p>컨텍스트를 함께 쓰므로 회원·임시 세션·해제 요청 행과 테스트용 서버의 기록은 각 테스트가 시작할 때 비운다. 지표는
 * 누적되므로 전후 차이로 본다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "auth.enabled=true",
    "auth.public-base-url=https://www.salmonbus.test",
    "auth.kakao.enabled=true",
    "auth.kakao.client-id=" + FakeKakaoServer.CLIENT_ID,
    "auth.kakao.client-secret=" + FakeKakaoServer.CLIENT_SECRET,
    "auth.apple.enabled=true",
    "auth.apple.client-id=" + FakeAppleServer.CLIENT_ID,
    "auth.apple.team-id=" + FakeAppleServer.TEAM_ID,
    "auth.apple.key-id=" + FakeAppleServer.KEY_ID,
})
@Import({PostgresTestContainer.class, FakeLoginProviders.class})
@MockitoSpyBean(types = SignInWithSocialAccount.class)
public @interface LoginIntegrationTest {
}
