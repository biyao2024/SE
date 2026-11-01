package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employee")
public class EmployeeAttendanceController {
    private final Store store;
    private final AuthController auth;
    private final AttendanceService attendance;
    private final AttendancePolicyService policy;

    public EmployeeAttendanceController(Store store, AuthController auth, AttendanceService attendance, AttendancePolicyService policy) {
        this.store = store;
        this.auth = auth;
        this.attendance = attendance;
        this.policy = policy;
    }

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    public Object profile() {
        var user = auth.current();
        return Api.ok(employeeView(currentEmployee(user), user));
    }

    @GetMapping("/home")
    @Transactional
    public Object home() {
        attendance.guard();
        var user = auth.current();
        var employee = currentEmployee(user);
        var today = attendance.today();
        var rule = attendance.rule(today);
        var rows = new ArrayList<Map<String, Object>>();
        LocalDate first = today.withDayOfMonth(1).isBefore(employee.startDate) ? employee.startDate : today.withDayOfMonth(1);
        for (var date = first; !date.isAfter(today); date = date.plusDays(1))
            rows.add(attendance.view(attendance.recalculate(employee, date), employee));
        var daily = rows.getLast();
        int leaves = store.list(LeaveRequest.class, "where e.employeeId=?1 and e.status='PENDING'", employee.id).size();
        int corrections = store.list(CorrectionRequest.class, "where e.employeeId=?1 and e.status='PENDING'", employee.id).size();
        var out = new LinkedHashMap<String, Object>();
        out.put("date", today);
        out.put("serverTime", attendance.now().atZone(attendance.zone()).toOffsetDateTime());
        out.put("employee", employeeView(employee, user));
        out.put("today", daily);
        out.put("rule", ruleView(rule));
        out.put("policy", policy.view());
        out.put("locations", policy.locations(false));
        out.put("monthSummary", attendance.summarize(rows));
        out.put("pendingRequestCount", leaves + corrections);
        out.put("pendingRequests", Map.of("leave", leaves, "correction", corrections));
        out.put("workday", attendance.workday(today));
        out.put("onLeave", attendance.leave(employee.id, today));
        out.put("inWindow", AttendanceEngine.inWindow(rule, "IN", attendance.now().toLocalTime()));
        out.put("outWindow", AttendanceEngine.inWindow(rule, "OUT", attendance.now().toLocalTime()));
        out.put("todayEvents", attendance.events(employee.id, today).stream().map(this::eventView).toList());
        return Api.ok(out);
    }

    @PostMapping("/location-punch")
    @Transactional
    public Object locationPunch(@RequestBody Map<String, Object> body) {
        // The shared guard makes policy changes, leave approvals and concurrent punches atomic.
        var settings = attendance.guard();
        var user = auth.current();
        var employee = currentEmployee(user);
        var input = input(body);
        String sessionId = "location:" + input.requestId();
        String requestHash = Crypto.hash(input.type() + "|" + input.latitude() + "|" + input.longitude()
            + "|" + input.accuracyMeters() + "|" + input.capturedAt());

        var existing = store.first(PunchEvent.class, "where e.sessionId=?1", sessionId);
        if (existing.isPresent()) {
            var event = existing.get();
            if (!employee.id.equals(event.employeeId) || !"LOCATION".equals(event.source)
                || !requestHash.equals(event.locationRequestHash))
                throw Api.error(409, "REQUEST_ID_CONFLICT", "此请求编号已使用，请重新发起打卡");
            // A lost response can be recovered after the original coordinates have aged or the policy changed.
            return Api.ok(punchView(event, employee, input.requestId(), true));
        }

        policy.requireLocationAllowed();
        var acceptedAt = attendance.now();
        var date = acceptedAt.toLocalDate();
        if (!attendance.workday(date)) throw Api.error(409, "NOT_WORKDAY", "今天不是工作日，无需打卡");
        if (attendance.leave(employee.id, date)) throw Api.error(409, "LEAVE_CONFLICT", "今日已批准整天请假，不能打卡");
        if (!AttendanceEngine.inWindow(attendance.rule(date), input.type(), acceptedAt.toLocalTime()))
            throw Api.error(400, "OUTSIDE_WINDOW", "当前不在对应打卡时段内");

        long nowMillis = acceptedAt.atZone(attendance.zone()).toInstant().toEpochMilli();
        if (input.capturedAt() < nowMillis - AttendancePolicyService.MAX_POSITION_AGE_SECONDS * 1000
            || input.capturedAt() > nowMillis + 5000)
            throw Api.error(400, "LOCATION_STALE", "位置信息已过期或设备时间不正确，请重新定位");
        var located = policy.locate(input.latitude(), input.longitude(), input.accuracyMeters());
        var previous = store.first(PunchEvent.class,
            "where e.employeeId=?1 and e.type=?2 and e.source<>'FACE_TEST' order by e.acceptedAt desc", employee.id, input.type());
        if (previous.isPresent() && previous.get().acceptedAt.toLocalDate().equals(date)
            && Duration.between(previous.get().acceptedAt, acceptedAt).toMillis() < 30_000)
            throw Api.error(429, "RATE_LIMIT", "30秒内已完成同类型打卡，请查看今日记录");

        var event = new PunchEvent();
        event.employeeId = employee.id;
        event.type = input.type();
        event.acceptedAt = acceptedAt;
        event.source = "LOCATION";
        event.sessionId = sessionId;
        event.livenessRequired = false;
        event.livenessStatus = "SKIPPED";
        event.configVersion = settings.configVersion;
        event.galleryVersion = settings.galleryVersion;
        event.locationId = located.location().id;
        event.locationName = located.location().name;
        event.distanceMeters = located.distanceMeters();
        event.accuracyMeters = input.accuracyMeters();
        event.locationRequestHash = requestHash;
        store.save(event);
        store.flush();
        attendance.audit(user.id, "LOCATION_PUNCH", event.id.toString(), event.type + ", location=" + event.locationId);
        return Api.ok(punchView(event, employee, input.requestId(), false));
    }

    private Employee currentEmployee(UserAccount user) {
        if (!user.enabled) throw Api.error(403, "ACCOUNT_DISABLED", "账号已停用");
        if (user.mustChangePassword) throw Api.error(403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
        if (user.employeeId == null) throw Api.error(400, "NO_EMPLOYEE", "当前账号尚未关联员工档案");
        var employee = store.get(Employee.class, user.employeeId);
        if (!employee.active || !attendance.employed(employee, attendance.today()))
            throw Api.error(403, "EMPLOYEE_DISABLED", "员工档案已停用或不在在职期间");
        return employee;
    }

    private Map<String, Object> employeeView(Employee employee, UserAccount user) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", employee.id);
        out.put("employeeNo", employee.employeeNo);
        out.put("name", employee.name);
        out.put("departmentId", employee.departmentId);
        out.put("departmentName", store.get(Department.class, employee.departmentId).name);
        out.put("startDate", employee.startDate);
        out.put("endDate", employee.endDate);
        out.put("active", employee.active);
        out.put("faceEnrolled", !store.list(FaceTemplate.class, "where e.employeeId=?1", employee.id).isEmpty());
        out.put("username", user.username);
        out.put("role", user.role);
        return out;
    }

    private Map<String, Object> ruleView(Rule rule) {
        return Map.of("id", rule.id, "effectiveDate", rule.effectiveDate,
            "startTime", rule.startTime, "endTime", rule.endTime,
            "inWindowStart", rule.inWindowStart, "inWindowEnd", rule.inWindowEnd,
            "outWindowStart", rule.outWindowStart, "outWindowEnd", rule.outWindowEnd,
            "lateGraceMinutes", rule.lateGraceMinutes, "earlyGraceMinutes", rule.earlyGraceMinutes);
    }

    private Map<String, Object> eventView(PunchEvent event) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", event.id);
        out.put("type", event.type);
        out.put("acceptedAt", event.acceptedAt);
        out.put("source", event.source);
        out.put("locationName", event.locationName);
        return out;
    }

    private Map<String, Object> punchView(PunchEvent event, Employee employee, String requestId, boolean duplicate) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", duplicate ? "DUPLICATE" : "SUCCESS");
        out.put("message", duplicate ? "本次打卡已记录" : "IN".equals(event.type) ? "上班签到成功" : "下班签退成功");
        out.put("eventId", event.id);
        out.put("requestId", requestId);
        out.put("acceptedAt", event.acceptedAt);
        out.put("type", event.type);
        out.put("source", event.source);
        out.put("locationId", event.locationId);
        out.put("locationName", event.locationName);
        out.put("distanceMeters", event.distanceMeters);
        out.put("accuracyMeters", event.accuracyMeters);
        out.put("daily", attendance.view(attendance.recalculate(employee, event.acceptedAt.toLocalDate()), employee));
        return out;
    }

    private record LocationInput(String type, double latitude, double longitude, double accuracyMeters, long capturedAt, String requestId) {}

    private LocationInput input(Map<String, Object> body) {
        String type = RequestController.type(Api.required(body, "type", 10));
        double latitude = AttendancePolicyService.number(body, "latitude");
        double longitude = AttendancePolicyService.number(body, "longitude");
        AttendancePolicyService.validateCoordinates(latitude, longitude);
        double accuracy = AttendancePolicyService.number(body, "accuracyMeters");
        if (accuracy <= 0 || accuracy > AttendancePolicyService.MAX_ACCURACY_METERS)
            throw Api.error(400, "LOCATION_INACCURATE", "定位精度须在100米以内，请到开阔位置重新定位");
        long capturedAt = AttendancePolicyService.integer(body, "capturedAt");
        String requestId = Api.required(body, "requestId", 36);
        try {
            String canonical = UUID.fromString(requestId).toString();
            if (!canonical.equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
            requestId = canonical;
        } catch (IllegalArgumentException ex) {
            throw Api.error(400, "INVALID_REQUEST_ID", "请求编号须为有效UUID");
        }
        return new LocationInput(type, latitude, longitude, accuracy, capturedAt, requestId);
    }
}
