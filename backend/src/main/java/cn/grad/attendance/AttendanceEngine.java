package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.time.*;
import java.util.*;

/** Pure calendar-day calculation: acceptance times are assigned only by Java. */
public final class AttendanceEngine {
    private AttendanceEngine() {}
    public record Result(LocalDateTime checkIn,LocalDateTime checkOut,String status,boolean late,boolean early,boolean required) {}
    public static boolean inWindow(Rule rule,String type,LocalTime time) {
        LocalTime start="IN".equals(type)?rule.inWindowStart:rule.outWindowStart,end="IN".equals(type)?rule.inWindowEnd:rule.outWindowEnd;
        return !time.isBefore(start)&&!time.isAfter(end);
    }
    public static Result calculate(LocalDate day,LocalDate today,Rule rule,boolean workday,boolean employed,boolean onLeave,List<PunchEvent> events,List<CorrectionRequest> corrections) {
        LocalDateTime in=events.stream().filter(e->"IN".equals(e.type)).map(e->e.acceptedAt).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime out=events.stream().filter(e->"OUT".equals(e.type)).map(e->e.acceptedAt).max(Comparator.naturalOrder()).orElse(null);
        for(var c:corrections.stream().sorted(Comparator.comparing((CorrectionRequest c)->c.updatedAt).thenComparing(c->c.id)).toList()) {
            if("IN".equals(c.type))in=day.atTime(c.requestedTime);else out=day.atTime(c.requestedTime);
        }
        boolean required=workday&&employed;
        boolean late=required&&!onLeave&&in!=null&&in.toLocalTime().isAfter(rule.startTime.plusMinutes(rule.lateGraceMinutes));
        boolean early=required&&!onLeave&&out!=null&&out.toLocalTime().isBefore(rule.endTime.minusMinutes(rule.earlyGraceMinutes));
        String status=!required?"REST":onLeave?"LEAVE":in!=null&&out!=null?"NORMAL":!day.isBefore(today)?"PENDING":in==null&&out==null?"ABSENT":in==null?"MISSING_IN":"MISSING_OUT";
        return new Result(in,out,status,late,early,required);
    }
}
