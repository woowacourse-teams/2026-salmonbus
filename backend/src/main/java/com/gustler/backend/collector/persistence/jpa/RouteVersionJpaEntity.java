package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.RouteContentDigest;
import com.gustler.backend.collector.RouteTimetable;
import com.gustler.backend.collector.RouteVersionContent;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity(name = "CollectorRouteVersion")
@Table(name = "route_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long routeId;

    private Integer turnSequence;

    private String upFirstDepartureTime;

    private String upLastDepartureTime;

    private String downFirstDepartureTime;

    private String downLastDepartureTime;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String contentDigest;

    private OffsetDateTime validFrom;

    private OffsetDateTime validTo;

    public RouteVersionJpaEntity(
        final long routeId,
        Integer turnSequence,
        RouteVersionContent content,
        OffsetDateTime validFrom
    ) {
        this.routeId = routeId;
        this.turnSequence = turnSequence;
        this.contentDigest = content.contentDigest().value();
        this.validFrom = validFrom;
        applyTimetable(content.timetable());
    }

    public void closeAt(
        OffsetDateTime closedAt
    ) {
        if (validTo != null) {
            throw new IllegalStateException("판본 %d 는 %s 에 이미 닫혔다".formatted(id, validTo));
        }
        this.validTo = closedAt;
    }

    public void revise(
        RouteTimetable timetable
    ) {
        applyTimetable(timetable);
    }

    public RouteVersionContent content() {
        return new RouteVersionContent(new RouteContentDigest(contentDigest), timetable());
    }

    private RouteTimetable timetable() {
        return new RouteTimetable(
            upFirstDepartureTime,
            upLastDepartureTime,
            downFirstDepartureTime,
            downLastDepartureTime
        );
    }

    private void applyTimetable(
        RouteTimetable timetable
    ) {
        this.upFirstDepartureTime = timetable.upFirstDepartureTime();
        this.upLastDepartureTime = timetable.upLastDepartureTime();
        this.downFirstDepartureTime = timetable.downFirstDepartureTime();
        this.downLastDepartureTime = timetable.downLastDepartureTime();
    }
}
