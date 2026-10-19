package com.gustler.backend.routecatalog.infrastructure.jpa;

import com.gustler.backend.routecatalog.domain.HeadwayRange;
import com.gustler.backend.routecatalog.domain.RouteContentDigest;
import com.gustler.backend.routecatalog.domain.RouteHeadways;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.RouteVersionContent;
import com.gustler.backend.routecatalog.domain.RouteVersion;
import jakarta.persistence.Column;
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

@Entity(name = "RouteVersion")
@Table(name = "route_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteVersionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "turn_sequence")
    private Integer turnSequence;

    @Column(name = "up_first_departure_time")
    private String upFirstDepartureTime;

    @Column(name = "up_last_departure_time")
    private String upLastDepartureTime;

    @Column(name = "down_first_departure_time")
    private String downFirstDepartureTime;

    @Column(name = "down_last_departure_time")
    private String downLastDepartureTime;

    @Column(name = "weekday_min_headway_minutes")
    private Integer weekdayMinHeadwayMinutes;

    @Column(name = "weekday_max_headway_minutes")
    private Integer weekdayMaxHeadwayMinutes;

    @Column(name = "saturday_min_headway_minutes")
    private Integer saturdayMinHeadwayMinutes;

    @Column(name = "saturday_max_headway_minutes")
    private Integer saturdayMaxHeadwayMinutes;

    @Column(name = "sunday_min_headway_minutes")
    private Integer sundayMinHeadwayMinutes;

    @Column(name = "sunday_max_headway_minutes")
    private Integer sundayMaxHeadwayMinutes;

    @Column(name = "holiday_min_headway_minutes")
    private Integer holidayMinHeadwayMinutes;

    @Column(name = "holiday_max_headway_minutes")
    private Integer holidayMaxHeadwayMinutes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_digest")
    private String contentDigest;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
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

    public RouteVersion toDomain() {
        return new RouteVersion(id, validFrom, validTo, content());
    }

    public void apply(RouteVersion version) {
        this.validTo = version.validTo();
        applyTimetable(version.content().timetable());
    }

    public RouteVersionContent content() {
        return new RouteVersionContent(new RouteContentDigest(contentDigest), timetable());
    }

    private RouteTimetable timetable() {
        return new RouteTimetable(
            upFirstDepartureTime,
            upLastDepartureTime,
            downFirstDepartureTime,
            downLastDepartureTime,
            new RouteHeadways(
                headwayOf(weekdayMinHeadwayMinutes, weekdayMaxHeadwayMinutes),
                headwayOf(saturdayMinHeadwayMinutes, saturdayMaxHeadwayMinutes),
                headwayOf(sundayMinHeadwayMinutes, sundayMaxHeadwayMinutes),
                headwayOf(holidayMinHeadwayMinutes, holidayMaxHeadwayMinutes))
        );
    }

    private HeadwayRange headwayOf(
        Integer minMinutes,
        Integer maxMinutes
    ) {
        return HeadwayRange.of(minMinutes, maxMinutes).orElse(null);
    }

    private void applyTimetable(
        RouteTimetable timetable
    ) {
        this.upFirstDepartureTime = timetable.upFirstDepartureTime();
        this.upLastDepartureTime = timetable.upLastDepartureTime();
        this.downFirstDepartureTime = timetable.downFirstDepartureTime();
        this.downLastDepartureTime = timetable.downLastDepartureTime();
        applyHeadways(timetable.headways());
    }

    private void applyHeadways(
        RouteHeadways headways
    ) {
        this.weekdayMinHeadwayMinutes = minMinutesOf(headways.weekday());
        this.weekdayMaxHeadwayMinutes = maxMinutesOf(headways.weekday());
        this.saturdayMinHeadwayMinutes = minMinutesOf(headways.saturday());
        this.saturdayMaxHeadwayMinutes = maxMinutesOf(headways.saturday());
        this.sundayMinHeadwayMinutes = minMinutesOf(headways.sunday());
        this.sundayMaxHeadwayMinutes = maxMinutesOf(headways.sunday());
        this.holidayMinHeadwayMinutes = minMinutesOf(headways.holiday());
        this.holidayMaxHeadwayMinutes = maxMinutesOf(headways.holiday());
    }

    private Integer minMinutesOf(
        HeadwayRange headway
    ) {
        return headway == null ? null : headway.minMinutes();
    }

    private Integer maxMinutesOf(
        HeadwayRange headway
    ) {
        return headway == null ? null : headway.maxMinutes();
    }
}
