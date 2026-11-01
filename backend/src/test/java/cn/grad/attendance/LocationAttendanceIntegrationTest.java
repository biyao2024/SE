package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:location-attendance-integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "app.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.internal-key=test-only-internal-key-never-use-in-deployment-12345",
    "app.bootstrap-password=TestPassword123!", "app.secure-cookie=false"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LocationAttendanceIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-22T01:05:00Z");
    static class TestClock extends Clock {
        Instant current = START;
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
    @TestConfiguration static class Time {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    @Autowired MockMvc mvc;
    @Autowired Store store;
    @Autowired AttendanceService attendance;
    @Autowired KioskService kiosk;
    @Autowired Crypto crypto;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @MockitoBean AlgorithmClient algorithm;

    @BeforeEach void setup() {
        clock.current = START;
        store.first(UserAccount.class, "where e.username='admin'").orElseThrow().mustChangePassword = false;
        when(algorithm.health()).thenAnswer(call -> Map.of("ready", true,
            "galleryVersion", attendance.settings().galleryVersion, "modelVersion", "test-model", "detail", ""));
    }

    Employee employee(String username) {
        var employee = new Employee();
        employee.employeeNo = username;
        employee.name = username + "员工";
        employee.departmentId = store.list(Department.class, "").getFirst().id;
        employee.startDate = attendance.today().minusDays(10);
        store.save(employee);
        var account = new UserAccount();
        account.username = username;
        account.employeeId = employee.id;
        account.passwordHash = "unused-mock-authentication-fixture";
        account.role = "EMPLOYEE";
        account.mustChangePassword = false;
        store.save(account);
        return employee;
    }

    ResultActions request(MockHttpServletRequestBuilder builder, Object body, String username, String role) throws Exception {
        return mvc.perform(builder.with(user(username).roles(role)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    ResultActions punch(String username, Map<String, Object> body) throws Exception {
        return request(post("/api/v1/employee/location-punch"), body, username, "EMPLOYEE");
    }
    Map<String, Object> position(String type) {
        return new LinkedHashMap<>(Map.of("type", type, "latitude", 31.2304, "longitude", 121.4737,
            "accuracyMeters", 15, "capturedAt", clock.instant().toEpochMilli(), "requestId", UUID.randomUUID().toString()));
    }
    Map<String, Object> location(String name, int radius, boolean active) {
        return Map.of("name", name, "address", "测试办公区", "latitude", 31.2304, "longitude", 121.4737,
            "radiusMeters", radius, "active", active);
    }
    long addLocation(String name, int radius, boolean active) throws Exception {
        return data(request(post("/api/v1/attendance-locations"), location(name, radius, active), "admin", "ADMIN")
            .andExpect(status().isOk())).get("id").asLong();
    }
    void mode(String next) throws Exception {
        request(put("/api/v1/attendance-policy"), Map.of("mode", next), "admin", "ADMIN")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value(next));
    }
    void locationReady(String next) throws Exception { addLocation("办公室", 200, true); mode(next); }
    JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString()).get("data");
    }
    void rejectedPosition(String username, String field, Object value, int expectedStatus, String code) throws Exception {
        var body = position("IN");
        body.put(field, value);
        punch(username, body).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
    }
    Device device() {
        var device = new Device(); device.name = "集成测试设备";
        device.tokenHash = Crypto.hash("location-test-device"); device.expiresAt = attendance.now().plusDays(1);
        return store.save(device);
    }
    PunchSession faceSession(Device device, Employee employee, boolean testMode) {
        var session = new PunchSession(); session.sessionId = UUID.randomUUID().toString();
        session.deviceId = device.id; session.type = "IN"; session.testMode = testMode; session.actions = "BLINK,MOUTH";
        session.configVersion = attendance.settings().configVersion; session.galleryVersion = attendance.settings().galleryVersion;
        session.expiresAt = attendance.now().plusSeconds(60); store.save(session);
        if (store.list(FaceTemplate.class, "where e.employeeId=?1", employee.id).isEmpty()) {
            var template = new FaceTemplate(); template.employeeId = employee.id; template.batch = "fixture";
            template.modelVersion = "test-model"; template.consent = true;
            template.encryptedVector = crypto.encrypt("[1,0]", employee.id); store.save(template);
        }
        return session;
    }
    Map<String, Object> faceResult(Employee employee) {
        return Map.of("employeeId", employee.id, "score", .9, "margin", .4,
            "galleryVersion", attendance.settings().galleryVersion, "configVersion", attendance.settings().configVersion,
            "modelVersion", "test-model", "livenessStatus", "SKIPPED");
    }

    @Test void anonymousAccessAndUnlinkedAdministratorCannotUseEmployeeEndpoints() throws Exception {
        for (String path : List.of("/employee/home", "/employee/profile", "/attendance-policy", "/attendance-locations"))
            mvc.perform(get("/api/v1" + path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/employee/location-punch").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(position("IN")))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/employee/home").with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_EMPLOYEE"));
    }

    @Test void employeesReadPolicyAndActiveLocationsButCannotManageEither() throws Exception {
        employee("READER");
        long active = addLocation("开放地点", 200, true);
        addLocation("停用地点", 200, false);
        mvc.perform(get("/api/v1/attendance-policy").with(user("READER").roles("EMPLOYEE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("FACE"));
        mvc.perform(get("/api/v1/attendance-locations").with(user("READER").roles("EMPLOYEE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/attendance-locations").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        request(put("/api/v1/attendance-policy"), Map.of("mode", "LOCATION"), "READER", "EMPLOYEE").andExpect(status().isForbidden());
        request(post("/api/v1/attendance-locations"), location("越权", 100, true), "READER", "EMPLOYEE").andExpect(status().isForbidden());
        request(put("/api/v1/attendance-locations/" + active), location("越权", 100, true), "READER", "EMPLOYEE").andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/attendance-locations/" + active).with(user("READER").roles("EMPLOYEE")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/attendance-policy").with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"LOCATION\"}")).andExpect(status().isForbidden());
        assertThat(attendance.settings().attendanceMode).isEqualTo("FACE");
    }

    @Test void locationModeRequiresAnActiveSiteAndRetainsOneUntilFaceModeIsRestored() throws Exception {
        request(put("/api/v1/attendance-policy"), Map.of("mode", "LOCATION"), "admin", "ADMIN")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        long id = addLocation("办公区", 200, true); mode("LOCATION");
        mvc.perform(delete("/api/v1/attendance-locations/" + id).with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        mode("FACE");
        mvc.perform(delete("/api/v1/attendance-locations/" + id).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
    }

    @Test void locationModeDisablesFormalFaceButKeepsDiagnosticTestEventsSeparate() throws Exception {
        var employee = employee("DIAGNOSTIC"); var device = device(); locationReady("LOCATION");
        var settings = attendance.settings(); settings.calibrationConfirmed = true;
        mvc.perform(get("/api/v1/kiosk/status").cookie(new jakarta.servlet.http.Cookie("PUNCH_DEVICE", "location-test-device")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(false));
        assertThatThrownBy(() -> kiosk.create("location-test-device", "IN"))
            .isInstanceOfSatisfying(Api.Failure.class, failure -> assertThat(failure.code).isEqualTo("FACE_DISABLED"));
        settings.testMode = true;
        request(post("/api/v1/kiosk/diagnostics/sessions").cookie(new jakarta.servlet.http.Cookie("PUNCH_DEVICE", "location-test-device")),
            Map.of("type", "IN"), "admin", "ADMIN").andExpect(status().isOk());
        var trial = faceSession(device, employee, true);
        assertThat(kiosk.finish(trial.sessionId, device.id, faceResult(employee)).get("status")).isEqualTo("SUCCESS");
        assertThat(store.list(PunchEvent.class, "where e.employeeId=?1", employee.id)).extracting(event -> event.source).containsExactly("FACE_TEST");
        assertThat(attendance.events(employee.id, attendance.today())).isEmpty();
        assertThat(store.list(Daily.class, "where e.employeeId=?1", employee.id)).isEmpty();
    }

    @Test void modeSwitchCannotTurnAnExistingFormalFaceSessionIntoATestBypass() {
        var employee = employee("MODE-SWITCH"); var device = device(); var session = faceSession(device, employee, false);
        attendance.settings().attendanceMode = "LOCATION";
        attendance.settings().testMode = true;
        assertThatThrownBy(() -> kiosk.finish(session.sessionId, device.id, faceResult(employee)))
            .isInstanceOfSatisfying(Api.Failure.class, failure -> assertThat(failure.code).isEqualTo("FACE_DISABLED"));
        assertThat(store.list(PunchEvent.class, "")).isEmpty();
    }

    @Test void employeeIdentityAndAcceptedTimeComeFromServer() throws Exception {
        var self = employee("SELF"); var other = employee("OTHER"); locationReady("LOCATION");
        for (String path : List.of("/employee/home", "/employee/profile")) {
            mvc.perform(get("/api/v1" + path).param("employeeId", other.id.toString()).with(user("SELF").roles("EMPLOYEE")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("SELF")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("OTHER员工"))));
        }
        var body = position("IN"); body.put("employeeId", other.id); body.put("acceptedAt", "2020-01-01T06:00:00");
        body.put("capturedAt", clock.instant().minusSeconds(25).toEpochMilli());
        punch("SELF", body).andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("LOCATION"));
        var event = store.list(PunchEvent.class, "").getFirst();
        assertThat(event.employeeId).isEqualTo(self.id); assertThat(event.acceptedAt).isEqualTo(attendance.now());
        assertThat(attendance.events(other.id, attendance.today())).isEmpty();
    }

    @Test void validatesDistanceAccuracyCoordinatesAndCaptureTime() throws Exception {
        employee("POSITION"); locationReady("LOCATION");
        rejectedPosition("POSITION", "latitude", 31.24, 403, "OUTSIDE_LOCATION");
        rejectedPosition("POSITION", "accuracyMeters", 101, 400, "LOCATION_INACCURATE");
        rejectedPosition("POSITION", "accuracyMeters", 0, 400, "LOCATION_INACCURATE");
        rejectedPosition("POSITION", "latitude", 91, 400, "INVALID_LOCATION");
        rejectedPosition("POSITION", "capturedAt", clock.instant().minusMillis(60_001).toEpochMilli(), 400, "LOCATION_STALE");
        rejectedPosition("POSITION", "capturedAt", clock.instant().plusMillis(5_001).toEpochMilli(), 400, "LOCATION_STALE");
        assertThat(store.list(PunchEvent.class, "")).isEmpty();
    }

    @Test void administratorCanPersistSiteSpecificIndoorToleranceWithoutChangingDefaultSites() throws Exception {
        employee("INDOOR"); long id = addLocation("室内场地", 300, true); mode("LOCATION");
        rejectedPosition("INDOOR", "accuracyMeters", 137, 400, "LOCATION_INACCURATE");
        var configuration = new LinkedHashMap<>(location("室内场地", 300, true));
        configuration.put("maxAccuracyMeters", 150);
        request(put("/api/v1/attendance-locations/" + id), configuration, "INDOOR", "EMPLOYEE").andExpect(status().isForbidden());
        request(put("/api/v1/attendance-locations/" + id), configuration, "admin", "ADMIN")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxAccuracyMeters").value(150));
        store.flush();
        mvc.perform(get("/api/v1/attendance-locations").with(user("INDOOR").roles("EMPLOYEE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].maxAccuracyMeters").value(150));
        var body = position("IN"); body.put("accuracyMeters", 137);
        punch("INDOOR", body).andExpect(status().isOk());
        assertThat(store.list(PunchEvent.class, "").getFirst().accuracyMeters).isEqualTo(137);
    }

    @Test void siteAccuracyLimitsRejectInvalidValuesAndLegacyUpdatesKeepTheConfiguredLimit() throws Exception {
        long id = addLocation("定位配置", 300, true);
        var configuration = new LinkedHashMap<>(location("定位配置", 300, true));
        for (var invalid : List.of(0, 501, 1.5)) {
            configuration.put("maxAccuracyMeters", invalid);
            request(put("/api/v1/attendance-locations/" + id), configuration, "admin", "ADMIN").andExpect(status().isBadRequest());
        }
        configuration.put("maxAccuracyMeters", 150);
        request(put("/api/v1/attendance-locations/" + id), configuration, "admin", "ADMIN").andExpect(status().isOk());
        request(put("/api/v1/attendance-locations/" + id), location("旧客户端更新", 300, true), "admin", "ADMIN")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.maxAccuracyMeters").value(150));
    }

    @Test void smallGeofenceRequiresAccuracyWithinHalfItsRadius() throws Exception {
        employee("PRECISE"); addLocation("小范围", 50, true); mode("LOCATION");
        rejectedPosition("PRECISE", "accuracyMeters", 26, 400, "LOCATION_INACCURATE");
        var body = position("IN"); body.put("accuracyMeters", 25);
        punch("PRECISE", body).andExpect(status().isOk());
    }

    @Test void locationRequiresEnabledModeAndIgnoresFaceTestModeBypasses() throws Exception {
        employee("FORMAL"); addLocation("办公区", 200, true);
        punch("FORMAL", position("IN")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCATION_DISABLED"));
        mode("LOCATION"); attendance.settings().testMode = true;
        punch("FORMAL", position("OUT")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OUTSIDE_WINDOW"));
        assertThat(store.list(PunchEvent.class, "")).isEmpty();
    }

    @Test void approvedLeaveAndNonWorkingDatesBlockLocationAttendance() throws Exception {
        var employee = employee("LEAVE"); locationReady("LOCATION");
        var leave = new LeaveRequest(); leave.employeeId = employee.id;
        leave.userId = store.first(UserAccount.class, "where e.employeeId=?1", employee.id).orElseThrow().id;
        leave.startDate = leave.endDate = attendance.today(); leave.type = "事假"; leave.reason = "fixture"; leave.status = "APPROVED"; store.save(leave);
        punch("LEAVE", position("IN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEAVE_CONFLICT"));
        leave.status = "REJECTED";
        var calendar = new CalendarDay(); calendar.date = attendance.today(); calendar.workday = false; store.save(calendar);
        punch("LEAVE", position("IN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_WORKDAY"));
        assertThat(store.list(PunchEvent.class, "")).isEmpty();
    }

    @Test void employmentDatesAndAccountStatusAreEnforced() throws Exception {
        var employee = employee("EMPLOYMENT"); locationReady("LOCATION");
        employee.startDate = attendance.today().plusDays(1);
        punch("EMPLOYMENT", position("IN")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMPLOYEE_DISABLED"));
        employee.startDate = attendance.today().minusDays(10); employee.endDate = attendance.today();
        punch("EMPLOYMENT", position("IN")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMPLOYEE_DISABLED"));
        employee.endDate = null; employee.active = false;
        punch("EMPLOYMENT", position("IN")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test void retriesAreIdempotentAndRequestIdsCannotBeReusedForDifferentPeopleOrPositions() throws Exception {
        var employee = employee("RETRY"); employee("RETRY-OTHER"); locationReady("LOCATION");
        var body = position("IN");
        var original = data(punch("RETRY", body).andExpect(status().isOk()));
        punch("RETRY", body).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DUPLICATE"))
            .andExpect(jsonPath("$.data.eventId").value(original.get("eventId").asLong()));
        var altered = new LinkedHashMap<>(body); altered.put("accuracyMeters", 16);
        punch("RETRY", altered).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        punch("RETRY-OTHER", body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(attendance.events(employee.id, attendance.today())).hasSize(1);
    }

    @Test void newRequestIdsRespectThirtySecondLimitAndKeepFirstCheckIn() throws Exception {
        var employee = employee("RATE"); locationReady("LOCATION");
        punch("RATE", position("IN")).andExpect(status().isOk());
        punch("RATE", position("IN")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMIT"));
        clock.current = START.plusSeconds(31);
        punch("RATE", position("IN")).andExpect(status().isOk());
        assertThat(attendance.events(employee.id, attendance.today())).hasSize(2);
        assertThat(store.list(Daily.class, "where e.employeeId=?1", employee.id)).hasSize(1);
        assertThat(store.list(Daily.class, "where e.employeeId=?1", employee.id).getFirst().checkIn)
            .isEqualTo(LocalDateTime.ofInstant(START, clock.getZone()));
    }

    @Test void mixedMethodsShareOneDailyRecordAndDeduplicateAcrossSources() throws Exception {
        var employee = employee("MIXED"); locationReady("FACE_OR_LOCATION"); var device = device();
        var first = faceSession(device, employee, false);
        assertThat(kiosk.finish(first.sessionId, device.id, faceResult(employee)).get("status")).isEqualTo("SUCCESS");
        punch("MIXED", position("IN")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMIT"));
        clock.current = Instant.parse("2026-10-22T10:00:00Z");
        punch("MIXED", position("OUT")).andExpect(status().isOk());
        var faceOut = faceSession(device, employee, false); faceOut.type = "OUT";
        assertThat(kiosk.finish(faceOut.sessionId, device.id, faceResult(employee)).get("status")).isEqualTo("DUPLICATE");
        assertThat(attendance.events(employee.id, attendance.today())).extracting(event -> event.source).containsExactly("FACE", "LOCATION");
        var daily = store.list(Daily.class, "where e.employeeId=?1", employee.id);
        assertThat(daily).hasSize(1); assertThat(daily.getFirst().status).isEqualTo("NORMAL");
        assertThat(daily.getFirst().checkIn.toLocalTime()).isEqualTo(LocalTime.of(9, 5));
        assertThat(daily.getFirst().checkOut.toLocalTime()).isEqualTo(LocalTime.of(18, 0));
    }
}
