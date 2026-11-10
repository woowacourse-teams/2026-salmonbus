package com.gustler.backend.api.salmon.application;

import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.salmon.application.WalkOption.Relation;
import java.util.List;

/** SAL-362 탑승 후보 응답을 판정 입력으로 옮긴다. 같은 방향만 남기는 일은 조립이 한다. */
public final class WalkOptions {
    private WalkOptions() {
    }

    public static List<WalkOption> from(BoardingOptions boarding) {
        return boarding.options().stream().map(WalkOptions::optionOf).toList();
    }

    private static WalkOption optionOf(Option option) {
        return new WalkOption(option.stop().stationId(), option.stop().stopSequence(),
            relationOf(option.relation()), option.fastestSeconds());
    }

    private static Relation relationOf(StopRelation relation) {
        return switch (relation) {
            case BEFORE -> Relation.BEFORE;
            case AFTER -> Relation.AFTER;
            case OPPOSITE -> Relation.OPPOSITE;
            case SAME_STATION -> Relation.SAME_STATION;
        };
    }
}
