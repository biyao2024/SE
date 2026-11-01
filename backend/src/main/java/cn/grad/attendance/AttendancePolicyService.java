package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AttendancePolicyService {
    public static final double MAX_ACCURACY_METERS = 500;
    public static final long MAX_POSITION_AGE_SECONDS = 60;
    private static final Set<String> MODES = Set.of("FACE", "LOCATION", "FACE_OR_LOCATION");
    private final Store store;
    private final AttendanceService attendance;

    public AttendancePolicyService(Store store, AttendanceService attendance) {
        this.store = store;
        this.attendance = attendance;
    }

    public String mode() { return attendance.settings().attendanceMode; }
    public boolean faceAllowed() { return Set.of("FACE", "FACE_OR_LOCATION").contains(mode()); }
    public boolean locationAllowed() { return Set.of("LOCATION", "FACE_OR_LOCATION").contains(mode()); }
    public void requireFaceAllowed() {
        if (!faceAllowed()) throw Api.error(403, "FACE_DISABLED", "当前考勤方式为定位打卡，请登录员工端打卡");
    }
    public void requireLocationAllowed() {
        if (!locationAllowed()) throw Api.error(403, "LOCATION_DISABLED", "当前未启用定位打卡，请使用刷脸打卡");
    }

    public Map<String, Object> view() {
        String current = mode();
        return Map.of("mode", current, "faceEnabled", Set.of("FACE", "FACE_OR_LOCATION").contains(current),
            "locationEnabled", Set.of("LOCATION", "FACE_OR_LOCATION").contains(current),
            "maxAccuracyMeters", MAX_ACCURACY_METERS, "maxPositionAgeSeconds", MAX_POSITION_AGE_SECONDS);
    }

    public List<AttendanceLocation> activeLocations() {
        return store.list(AttendanceLocation.class, "where e.active=true and e.deleted=false order by e.id");
    }

    public List<Map<String, Object>> locations(boolean includeInactive) {
        return store.list(AttendanceLocation.class, includeInactive
            ? "where e.deleted=false order by e.id" : "where e.deleted=false and e.active=true order by e.id")
            .stream().map(this::locationView).toList();
    }

    public Map<String, Object> locationView(AttendanceLocation location) {
        return Map.of("id", location.id, "name", location.name, "address", location.address,
            "latitude", location.latitude, "longitude", location.longitude,
            "radiusMeters", location.radiusMeters, "maxAccuracyMeters", location.maxAccuracyMeters, "active", location.active);
    }

    /** Call after obtaining AttendanceService.guard(). */
    public Map<String, Object> updateMode(String next, Long actorId) {
        if (!MODES.contains(next)) throw Api.error(400, "INVALID_MODE", "考勤方式须为刷脸、定位或两者任选");
        if (!"FACE".equals(next) && activeLocations().isEmpty())
            throw Api.error(409, "LOCATION_REQUIRED", "请先添加并启用至少一个考勤地点");
        var settings = attendance.settings();
        if (!next.equals(settings.attendanceMode)) {
            settings.attendanceMode = next;
            settings.configVersion++;
            attendance.audit(actorId, "UPDATE_ATTENDANCE_POLICY", "system", "mode=" + next);
        }
        return view();
    }

    /** Call after obtaining AttendanceService.guard(). */
    public Map<String, Object> saveLocation(Long id, Map<String, Object> body, Long actorId) {
        var location = id == null ? new AttendanceLocation() : existingLocation(id);
        String name = Api.required(body, "name", 100);
        String address = body.get("address") == null ? "" : text(body, "address", 255);
        double latitude = number(body, "latitude"), longitude = number(body, "longitude");
        validateCoordinates(latitude, longitude);
        long radius = integer(body, "radiusMeters");
        if (radius < 50 || radius > 5000) throw Api.error(400, "INVALID_LOCATION", "考勤半径须为50至5000米的整数");
        long accuracy = body.containsKey("maxAccuracyMeters") ? integer(body, "maxAccuracyMeters") : location.maxAccuracyMeters;
        if (accuracy < 1 || accuracy > MAX_ACCURACY_METERS)
            throw Api.error(400, "INVALID_LOCATION", "允许定位误差须为1至500米的整数");
        boolean active = body.containsKey("active") ? booleanValue(body.get("active")) : true;
        if (!active && location.active && location.id != null) requireOtherActiveLocation(location.id);
        location.name = name;
        location.address = address;
        location.latitude = latitude;
        location.longitude = longitude;
        location.radiusMeters = (int) radius;
        location.maxAccuracyMeters = (int) accuracy;
        location.active = active;
        if (id == null) store.save(location);
        attendance.audit(actorId, id == null ? "CREATE_ATTENDANCE_LOCATION" : "UPDATE_ATTENDANCE_LOCATION",
            location.id.toString(), location.name + ", radius=" + radius + ", accuracy=" + accuracy + ", active=" + active);
        return locationView(location);
    }

    /** Soft deletion keeps previously accepted attendance evidence intact. */
    public void deleteLocation(Long id, Long actorId) {
        var location = existingLocation(id);
        if (location.active) requireOtherActiveLocation(id);
        location.active = false;
        location.deleted = true;
        attendance.audit(actorId, "DELETE_ATTENDANCE_LOCATION", id.toString(), location.name);
    }

    private AttendanceLocation existingLocation(Long id) {
        var location = store.get(AttendanceLocation.class, id);
        if (location.deleted) throw Api.error(404, "NOT_FOUND", "考勤地点不存在");
        return location;
    }

    private void requireOtherActiveLocation(Long excluded) {
        if (locationAllowed() && activeLocations().stream().noneMatch(location -> !location.id.equals(excluded)))
            throw Api.error(409, "LOCATION_REQUIRED", "定位打卡至少需要一个启用地点，请先调整考勤方式或添加其他地点");
    }

    public record Located(AttendanceLocation location, double distanceMeters) {}

    public Located locate(double latitude, double longitude, double accuracyMeters) {
        validateCoordinates(latitude, longitude);
        if (!Double.isFinite(accuracyMeters) || accuracyMeters <= 0 || accuracyMeters > MAX_ACCURACY_METERS)
            throw Api.error(400, "LOCATION_INACCURATE", "定位误差超过系统上限500米，请重新定位");
        var locations = activeLocations();
        if (locations.isEmpty()) throw Api.error(409, "LOCATION_UNAVAILABLE", "暂无启用的考勤地点，请联系管理员");
        var inside = locations.stream().map(location -> new Located(location,
                distanceMeters(latitude, longitude, location.latitude, location.longitude)))
            .filter(located -> located.distanceMeters() <= located.location().radiusMeters)
            .sorted(Comparator.comparingDouble(Located::distanceMeters)).toList();
        if (inside.isEmpty()) throw Api.error(403, "OUTSIDE_LOCATION", "当前位置不在启用的考勤地点范围内");
        return inside.stream().filter(located -> accuracyMeters <= accuracyLimit(located.location()))
            .findFirst().orElseThrow(() -> Api.error(400, "LOCATION_INACCURATE",
                "当前估计误差约±" + Math.ceil(accuracyMeters) + "米，此处允许误差不超过"
                + inside.stream().mapToDouble(located -> accuracyLimit(located.location())).max().orElse(100)
                + "米；请重新定位或联系管理员核对地点配置"));
    }

    private static double accuracyLimit(AttendanceLocation location) {
        return Math.min(MAX_ACCURACY_METERS, Math.min(location.maxAccuracyMeters, location.radiusMeters / 2.0));
    }

    /** Great-circle distance for browser WGS84 latitude/longitude, in meters. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latitude = Math.toRadians(lat2 - lat1), longitude = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(latitude / 2), 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(longitude / 2), 2);
        return 6_371_008.8 * 2 * Math.atan2(Math.sqrt(Math.min(1, Math.max(0, a))), Math.sqrt(Math.max(0, 1 - a)));
    }

    public static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw Api.error(400, "INVALID_LOCATION", "经纬度无效");
    }
    public static double number(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw Api.error(400, "INVALID_LOCATION", key + " 须为有效数值");
        return value.doubleValue();
    }
    public static long integer(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Number value)) throw Api.error(400, "INVALID_LOCATION", key + " 须为整数");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw Api.error(400, "INVALID_LOCATION", key + " 须为有效整数"); }
    }
    private static String text(Map<String, Object> body, String key, int max) {
        if (!(body.get(key) instanceof String value) || value.length() > max) throw Api.error(400, "INVALID_INPUT", key + " 格式不正确或超过长度限制");
        return value.trim();
    }
    private static boolean booleanValue(Object value) {
        if (!(value instanceof Boolean result)) throw Api.error(400, "INVALID_INPUT", "active 须为布尔值");
        return result;
    }
}
