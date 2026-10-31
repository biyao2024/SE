package cn.grad.attendance;

import jakarta.persistence.*;
import java.time.*;

/** Entities are intentionally separate from the external API DTOs. */
public final class Domain {
    private Domain() {}
    @MappedSuperclass public static abstract class Row {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        @Version public long version;
        @PrePersist void create() { createdAt=updatedAt=LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
        @PreUpdate void update() { updatedAt=LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    }
    @Entity(name="UserAccount") @Table(name="sys_user") public static class UserAccount extends Row {
        @Column(unique=true,nullable=false) public String username;
        @Column(nullable=false) public String passwordHash;
        public String role;
        public Long employeeId;
        public boolean enabled=true;
        public boolean mustChangePassword=true;
    }
    @Entity(name="Department") @Table(name="department") public static class Department extends Row {
        @Column(unique=true) public String name;
        public boolean active=true;
    }
    @Entity(name="Employee") @Table(name="employee") public static class Employee extends Row {
        @Column(unique=true) public String employeeNo;
        public String name;
        public Long departmentId;
        public boolean active=true;
        public LocalDate startDate;
        /** First excluded date; null means ongoing employment. */
        public LocalDate endDate;
    }
    @Entity(name="FaceTemplate") @Table(name="face_template") public static class FaceTemplate extends Row {
        public Long employeeId;
        @Column(columnDefinition="TEXT") public String encryptedVector;
        public String modelVersion;
        public String batch;
        public boolean consent;
    }
    @Entity(name="Rule") @Table(name="attendance_rule") public static class Rule extends Row {
        @Column(unique=true) public LocalDate effectiveDate;
        public LocalTime startTime=LocalTime.of(9,0),endTime=LocalTime.of(18,0);
        public int lateGraceMinutes=5,earlyGraceMinutes=5;
        public LocalTime inWindowStart=LocalTime.of(6,0),inWindowEnd=LocalTime.of(11,59,59);
        public LocalTime outWindowStart=LocalTime.of(12,0),outWindowEnd=LocalTime.of(23,59,59);
    }
    @Entity(name="CalendarDay") @Table(name="work_calendar") public static class CalendarDay extends Row {
        @Column(unique=true) public LocalDate date;
        public boolean workday;
        public String note;
    }
    @Entity(name="Device") @Table(name="punch_device") public static class Device extends Row {
        public String name;
        @Column(unique=true) public String tokenHash;
        public LocalDateTime expiresAt;
        public boolean revoked;
    }
    @Entity(name="PunchSession") @Table(name="punch_session") public static class PunchSession extends Row {
        @Column(unique=true) public String sessionId;
        public Long deviceId;
        public String type;
        public String actions;
        public boolean testMode;
        public boolean livenessRequired;
        public long configVersion,galleryVersion;
        public LocalDateTime expiresAt;
        public String status="PENDING";
        public String resultMessage;
        public Long employeeId;
    }
    @Entity(name="PunchEvent") @Table(name="punch_event") public static class PunchEvent extends Row {
        public Long employeeId;
        public String type;
        public LocalDateTime acceptedAt;
        public String source;
        public Long deviceId;
        @Column(unique=true) public String sessionId;
        public boolean livenessRequired;
        public String livenessStatus;
        public String modelVersion;
        public long configVersion,galleryVersion;
    }
    @Entity(name="Daily") @Table(name="attendance_daily",uniqueConstraints=@UniqueConstraint(columnNames={"employee_id","date"})) public static class Daily extends Row {
        public Long employeeId;
        public LocalDate date;
        public LocalDateTime checkIn,checkOut;
        public String status;
        public boolean late,early,onLeave,required;
        public int correctionCount;
        public Long ruleId;
        @Column(length=2000) public String ruleSnapshot;
    }
    @Entity(name="LeaveRequest") @Table(name="leave_request") public static class LeaveRequest extends Row {
        public Long employeeId,userId;
        public LocalDate startDate,endDate;
        public String type;
        @Column(length=1000) public String reason;
        public String status="PENDING";
        public Long reviewerId;
        public String comment;
    }
    @Entity(name="CorrectionRequest") @Table(name="correction_request") public static class CorrectionRequest extends Row {
        public Long employeeId,userId;
        public LocalDate date;
        public String type;
        public LocalTime requestedTime;
        @Column(length=1000) public String reason;
        public String status="PENDING";
        public Long reviewerId;
        public String comment;
        /** Unique only while pending, NULL permits historical rejected/approved requests. */
        @Column(unique=true) public String pendingKey;
    }
    @Entity(name="Settings") @Table(name="system_setting") public static class Settings extends Row {
        public boolean livenessEnabled=true;
        public boolean testMode;
        public double recognitionThreshold=0.5,candidateMargin=0.08;
        public boolean calibrationConfirmed;
        public long configVersion=1,galleryVersion=1;
    }
    @Entity(name="Audit") @Table(name="audit_log") public static class Audit extends Row {
        public Long actorId;
        public String action;
        public String target;
        @Column(length=1000) public String detail;
    }
}
