package com.gustler.backend.api.favorite.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FavoriteGroupNameTest {
    @Test
    void 앞뒤_공백을_제거한다() {
        assertThat(new FavoriteGroupName("  출근  ").value()).isEqualTo("출근");
    }

    @Test
    void 결합된_이모지는_사용자가_보는_한_문자로_센다() {
        assertThat(new FavoriteGroupName("👨‍👩‍👧‍👦출근길").value())
            .isEqualTo("👨‍👩‍👧‍👦출근길");
    }

    @Test
    void 빈_이름은_허용하지_않는다() {
        assertThatThrownBy(() -> new FavoriteGroupName("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사용자에게_보이는_문자가_10개를_넘으면_허용하지_않는다() {
        assertThatThrownBy(() -> new FavoriteGroupName("가나다라마바사아자차카"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
