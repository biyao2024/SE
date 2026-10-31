package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {
    final Store store;private final Clock clock;final ObjectMapper json;
    public AttendanceService(Store store,Clock clock,ObjectMapper json) {this.store=store;this.clock=clock;this.json=json;}
    public LocalDate today() {return LocalDate.now(clock);}
    public LocalDateTime now() {return LocalDateTime.now(clock);}
    public ZoneId zone() {return clock.getZone();}
    public Settings settings() {return store.list(Settings.class,"order by e.id").getFirst();}
    /** Small deployment: serialize business writes before acquiring employee/session locks. */
    public Settings guard() {return store.lockFirst(Settings.class);}
    public Rule rule(LocalDate date) {return store.first(Rule.class,"where e.effectiveDate<=?1 order by e.effectiveDate desc",date).orElseThrow();}
    public boolean workday(LocalDate date) {return store.first(CalendarDay.class,"where e.date=?1",date).map(d->d.workday).orElse(date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY);}
    public boolean employed(Employee e,LocalDate date) {return !date.isBefore(e.startDate)&&(e.endDate==null||date.isBefore(e.endDate));}
    public boolean leave(Long employeeId,LocalDate date) {return !store.list(LeaveRequest.class,"where e.employeeId=?1 and e.status='APPROVED' and e.startDate<=?2 and e.endDate>=?2",employeeId,date).isEmpty();}
    public List<PunchEvent> events(Long employeeId,LocalDate date) {return store.list(PunchEvent.class,"where e.source<>'FACE_TEST' and e.employeeId=?1 and e.acceptedAt>=?2 and e.acceptedAt<?3 order by e.acceptedAt",employeeId,date.atStartOfDay(),date.plusDays(1).atStartOfDay());}
    public List<CorrectionRequest> corrections(Long employeeId,LocalDate date) {return store.list(CorrectionRequest.class,"where e.employeeId=?1 and e.date=?2 and e.status='APPROVED'",employeeId,date);}
    public Daily recalculate(Employee employee,LocalDate date) {
        Daily daily=store.first(Daily.class,"where e.employeeId=?1 and e.date=?2",employee.id,date).orElseGet(()->{var d=new Daily();d.employeeId=employee.id;d.date=date;return d;});
        Rule rule=daily.ruleId==null?rule(date):store.get(Rule.class,daily.ruleId);
        boolean onLeave=leave(employee.id,date);var fixes=corrections(employee.id,date);
        var result=AttendanceEngine.calculate(date,today(),rule,workday(date),employed(employee,date),onLeave,events(employee.id,date),fixes);
        daily.checkIn=result.checkIn();daily.checkOut=result.checkOut();daily.status=result.status();daily.late=result.late();daily.early=result.early();daily.required=result.required();daily.onLeave=onLeave&&daily.required;daily.correctionCount=fixes.size();daily.ruleId=rule.id;
        if(daily.ruleSnapshot==null)try{daily.ruleSnapshot=json.writeValueAsString(rule);}catch(Exception ex){throw new IllegalStateException(ex);}
        if(daily.id==null)store.save(daily);return daily;
    }
    public void validateRange(LocalDate from,LocalDate to) {if(to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>365)throw Api.error(400,"INVALID_RANGE","日期范围须在366天以内");}
    @Transactional public List<Map<String,Object>> query(Long ownEmployee,LocalDate from,LocalDate to,Long employeeId,Long departmentId,String status) {
        validateRange(from,to);guard();
        var result=new ArrayList<Map<String,Object>>();
        for(var e:store.list(Employee.class,"order by e.employeeNo")) {
            if(ownEmployee!=null&&!ownEmployee.equals(e.id)||employeeId!=null&&!employeeId.equals(e.id)||departmentId!=null&&!departmentId.equals(e.departmentId))continue;
            for(var d=from;!d.isAfter(to)&&!d.isAfter(today());d=d.plusDays(1)) {
                if(d.isBefore(e.startDate))continue;
                var daily=recalculate(e,d);
                if(status!=null&&!status.isBlank()&&!status.equals(daily.status)&&!(status.equals("LATE")&&daily.late)&&!(status.equals("EARLY")&&daily.early))continue;
                result.add(view(daily,e));
            }
        }
        result.sort(Comparator.comparing((Map<String,Object> d)->d.get("date").toString()).reversed().thenComparing(d->d.get("employeeNo").toString()));return result;
    }
    public Map<String,Object> view(Daily d,Employee e) {
        var out=new LinkedHashMap<String,Object>();out.put("id",d.id);out.put("date",d.date);out.put("employeeId",e.id);out.put("employeeNo",e.employeeNo);out.put("employeeName",e.name);out.put("departmentName",store.get(Department.class,e.departmentId).name);out.put("checkIn",d.checkIn);out.put("checkOut",d.checkOut);out.put("status",d.status);out.put("late",d.late);out.put("early",d.early);out.put("leave",d.onLeave);out.put("required",d.required);out.put("correctionCount",d.correctionCount);return out;
    }
    public Map<String,Object> summarize(List<Map<String,Object>> rows) {
        var out=new LinkedHashMap<String,Object>();
        out.put("requiredDays",rows.stream().filter(r->Boolean.TRUE.equals(r.get("required"))).count());
        out.put("completedDays",rows.stream().filter(r->Boolean.TRUE.equals(r.get("required"))&&r.get("checkIn")!=null&&r.get("checkOut")!=null).count());
        out.put("lateCount",rows.stream().filter(r->Boolean.TRUE.equals(r.get("late"))).count());out.put("earlyCount",rows.stream().filter(r->Boolean.TRUE.equals(r.get("early"))).count());
        out.put("missingDays",rows.stream().filter(r->r.get("status").toString().startsWith("MISSING")).count());out.put("absentDays",rows.stream().filter(r->r.get("status").equals("ABSENT")).count());out.put("leaveDays",rows.stream().filter(r->r.get("status").equals("LEAVE")).count());out.put("correctionCount",rows.stream().mapToInt(r->(Integer)r.get("correctionCount")).sum());
        var dates=new TreeSet<String>();rows.forEach(r->dates.add(r.get("date").toString()));
        out.put("trend",dates.stream().map(d->Map.of("date",d,"completed",rows.stream().filter(r->r.get("date").toString().equals(d)&&r.get("status").equals("NORMAL")).count(),"absent",rows.stream().filter(r->r.get("date").toString().equals(d)&&r.get("status").equals("ABSENT")).count())).toList());
        out.put("distribution",List.of("NORMAL","PENDING","ABSENT","MISSING_IN","MISSING_OUT","LEAVE","REST").stream().map(s->Map.of("name",s,"value",rows.stream().filter(r->r.get("status").equals(s)).count())).toList());return out;
    }
    @Scheduled(initialDelay=30_000,fixedDelay=3_600_000) @Transactional public void catchUp() {
        if(store.list(Settings.class,"").isEmpty())return;guard();
        for(var e:store.list(Employee.class,"")) {
            // Historical rows are final unless an approved correction/leave explicitly recalculates them.
            var existing=store.list(Daily.class,"where e.employeeId=?1",e.id);var done=new HashSet<LocalDate>();
            for(var row:existing)if(!"PENDING".equals(row.status))done.add(row.date);
            var until=e.endDate==null?today():e.endDate.minusDays(1);
            for(var day=e.startDate;!day.isAfter(until)&&!day.isAfter(today());day=day.plusDays(1))if(!done.contains(day))recalculate(e,day);
        }
    }
    @Transactional public List<Map<String,Object>> testEvents(Long own,LocalDate start,LocalDate end) {
        var records=new ArrayList<Map<String,Object>>();
        for(var event:store.list(cn.grad.attendance.Domain.PunchEvent.class,"where e.source='FACE_TEST' and e.acceptedAt>=?1 and e.acceptedAt<?2 order by e.acceptedAt desc",start.atStartOfDay(),end.plusDays(1).atStartOfDay())) {
            if(own!=null&&!own.equals(event.employeeId))continue;
            var employee=store.get(cn.grad.attendance.Domain.Employee.class,event.employeeId);
            records.add(Map.of("id",event.id,"employeeName",employee.name,"employeeNo",employee.employeeNo,"acceptedAt",event.acceptedAt,"type",event.type,"source",event.source,"livenessStatus",event.livenessStatus));
        }
        return records;
    }
    public void audit(Long actor,String action,String target,String detail) {var a=new Audit();a.actorId=actor;a.action=action;a.target=target;a.detail=detail;store.save(a);}
}
