package com.gustler.backend.api.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.chat.dto.ChatMessageResponse;
import com.gustler.backend.api.chat.dto.ChatServerFrame.HistoryBatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IndependentChatContractsTest {
    @Test
    void 같은_세션을_다른_주소로_재접수해도_원래_슬롯은_한_번만_점유하고_반환한다() {
        // given
        ChatConnectionLimiter limiter = new ChatConnectionLimiter(2, 1);
        assertThat(limiter.tryAcquire("first", "address-a")).isTrue();

        // when
        final boolean duplicate = limiter.tryAcquire("first", "address-b");
        final boolean originalAddressBlocked = limiter.tryAcquire("second", "address-a");
        final boolean otherAddressAccepted = limiter.tryAcquire("third", "address-b");
        limiter.release("first");
        limiter.release("first");
        final boolean originalAddressAccepted = limiter.tryAcquire("second", "address-a");
        final boolean totalLimitEnforced = limiter.tryAcquire("fourth", "address-c");

        // then
        assertSoftly(softly -> {
            softly.assertThat(duplicate).isTrue();
            softly.assertThat(originalAddressBlocked).isFalse();
            softly.assertThat(otherAddressAccepted).isTrue();
            softly.assertThat(originalAddressAccepted).isTrue();
            softly.assertThat(totalLimitEnforced).isFalse();
        });
    }

    @Test
    void 이력_프레임은_null_목록과_null_원소를_허용하면서_입력_목록_수정과_분리한다() {
        // given
        List<ChatMessageResponse> messages = new ArrayList<>(Arrays.asList((ChatMessageResponse) null));
        HistoryBatch batch = new HistoryBatch(1, "history.batch", messages);
        HistoryBatch absent = new HistoryBatch(1, "history.batch", null);
        ChatFrameCodec codec = new ChatFrameCodec(JsonMapper.builder().findAndAddModules().build());
        String originalJson = codec.encode(batch);

        // when
        messages.clear();
        Throwable mutation = catchThrowable(() -> batch.messages().clear());

        // then
        assertSoftly(softly -> {
            softly.assertThat(batch.messages()).containsExactly((ChatMessageResponse) null);
            softly.assertThat(codec.encode(batch)).isEqualTo(originalJson);
            softly.assertThat(absent.messages()).isNull();
            softly.assertThat(mutation).isInstanceOf(UnsupportedOperationException.class);
        });
    }
}
