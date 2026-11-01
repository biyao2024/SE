package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttendancePolicyServiceTest {
    private final Store store = mock(Store.class);
    private final AttendanceService attendance = mock(AttendanceService.class);
    private final AttendancePolicyService policy = new AttendancePolicyService(store, attendance);

    private AttendanceLocation location(long id, double latitude, double longitude, int radius) {
        var location = new AttendanceLocation();
        location.id = id;
        location.latitude = latitude;
        location.longitude = longitude;
        location.radiusMeters = radius;
        return location;
    }

    private void locations(AttendanceLocation... locations) {
        when(store.list(AttendanceLocation.class, "where e.active=true and e.deleted=false order by e.id"))
            .thenReturn(List.of(locations));
    }

    @Test void sphericalDistanceHandlesCoincidentPointsDatelineAndAntipodes() {
        assertThat(AttendancePolicyService.distanceMeters(31, 121, 31, 121)).isZero();
        assertThat(AttendancePolicyService.distanceMeters(0, 0, 0, 1)).isCloseTo(111_195.08, within(0.1));
        assertThat(AttendancePolicyService.distanceMeters(0, 179.999, 0, -179.999)).isCloseTo(222.390, within(0.1));
        assertThat(AttendancePolicyService.distanceMeters(90, 0, -90, 180)).isFinite().isBetween(20_000_000.0, 20_020_000.0);
    }

    @Test void acceptsAnyContainingSiteWhoseRadiusSupportsTheAccuracy() {
        var small = location(1, 31, 121, 50);
        var large = location(2, 31, 121.0001, 300);
        locations(small, large);
        assertThat(policy.locate(31, 121, 50).location()).isSameAs(large);
        assertThat(policy.locate(31, 121, 25).location()).isSameAs(small);
    }

    @Test void rejectsImpreciseCoordinatesEvenAtTheSiteCenter() {
        locations(location(1, 31, 121, 50));
        assertThatThrownBy(() -> policy.locate(31, 121, 26)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("LOCATION_INACCURATE"));
        assertThatThrownBy(() -> policy.locate(31, 121, 101)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("LOCATION_INACCURATE"));
    }

    @Test void rejectsOutsidePointsAndUnavailableLocations() {
        locations(location(1, 31, 121, 100));
        assertThatThrownBy(() -> policy.locate(31.01, 121, 10)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("OUTSIDE_LOCATION"));
        locations();
        assertThatThrownBy(() -> policy.locate(31, 121, 10)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("LOCATION_UNAVAILABLE"));
    }

    @Test void indoorToleranceMustBeExplicitAndCannotOverrideRadiusOrOutsideRejection() {
        var site = location(1, 31, 121, 300);
        locations(site);
        assertThatThrownBy(() -> policy.locate(31, 121, 137)).isInstanceOf(Api.Failure.class);
        site.maxAccuracyMeters = 150;
        assertThat(policy.locate(31, 121, 137).location()).isSameAs(site);
        site.radiusMeters = 200;
        assertThatThrownBy(() -> policy.locate(31, 121, 137)).isInstanceOf(Api.Failure.class);
        site.radiusMeters = 300;
        assertThatThrownBy(() -> policy.locate(31.01, 121, 137)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("OUTSIDE_LOCATION"));
    }

    @Test void rejectsInvalidCoordinatesAndFractionalOrOverflowingIntegerFields() {
        assertThatThrownBy(() -> AttendancePolicyService.validateCoordinates(Double.NaN, 0)).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(() -> AttendancePolicyService.validateCoordinates(0, 181)).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(() -> AttendancePolicyService.number(Map.of("latitude", "31"), "latitude")).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(() -> AttendancePolicyService.integer(Map.of("capturedAt", 123.5), "capturedAt")).isInstanceOf(Api.Failure.class);
        assertThatThrownBy(() -> AttendancePolicyService.integer(Map.of("capturedAt", Double.MAX_VALUE), "capturedAt")).isInstanceOf(Api.Failure.class);
    }

    @Test void rejectsDisablingLastSiteWhenLocationAttendanceIsEnabled() {
        var settings = new Settings();
        settings.attendanceMode = "LOCATION";
        when(attendance.settings()).thenReturn(settings);
        var only = location(1, 31, 121, 100);
        only.name = "办公区";
        locations(only);
        when(store.get(AttendanceLocation.class, 1L)).thenReturn(only);
        assertThatThrownBy(() -> policy.deleteLocation(1L, 2L)).isInstanceOfSatisfying(Api.Failure.class,
            error -> assertThat(error.code).isEqualTo("LOCATION_REQUIRED"));
        assertThat(only.active).isTrue();
        assertThat(only.deleted).isFalse();
    }
}
