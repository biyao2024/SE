package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class RequestController {
    private final Store store;private final AuthController auth;private final AttendanceService attendance;
    public RequestController(Store store,AuthController auth,AttendanceService attendance) {this.store=store;this.auth=auth;this.attendance=attendance;}
    @GetMapping("/leave-requests") Object leaves(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status) {var u=auth.current();return Api.ok(Api.page(store.list(LeaveRequest.class,"order by e.id desc").stream().filter(r->u.role.equals("ADMIN")||Objects.equals(r.employeeId,u.employeeId)).filter(r->status==null||status.isBlank()||r.status.equals(status)).map(this::view).toList(),page,size));}
    @GetMapping("/correction-requests") Object corrections(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status) {var u=auth.current();return Api.ok(Api.page(store.list(CorrectionRequest.class,"order by e.id desc").stream().filter(r->u.role.equals("ADMIN")||Objects.equals(r.employeeId,u.employeeId)).filter(r->status==null||status.isBlank()||r.status.equals(status)).map(this::view).toList(),page,size));}
    @PostMapping("/leave-requests") @Transactional Object createLeave(@RequestBody Map<String,Object> body) {
        attendance.guard();Long employeeId=auth.employee();var employee=store.get(Employee.class,employeeId);var r=new LeaveRequest();r.employeeId=employeeId;r.userId=auth.current().id;r.startDate=LocalDate.parse(Api.required(body,"startDate",10));r.endDate=LocalDate.parse(Api.required(body,"endDate",10));attendance.validateRange(r.startDate,r.endDate);
        if(r.startDate.isBefore(employee.startDate)||r.endDate.isAfter(attendance.today().plusYears(1))||r.startDate.isBefore(attendance.today().minusYears(1)))throw Api.error(400,"INVALID_DATE","请假日期须在在职区间且距今一年以内");
        if(!store.list(LeaveRequest.class,"where e.employeeId=?1 and e.status in ('PENDING','APPROVED') and e.startDate<=?2 and e.endDate>=?3",employeeId,r.endDate,r.startDate).isEmpty())throw Api.error(409,"LEAVE_OVERLAP","与待审批或已批准请假重叠");
        r.type=Api.required(body,"type",50);r.reason=Api.required(body,"reason",1000);store.save(r);attendance.audit(r.userId,"CREATE_LEAVE",r.id.toString(),r.startDate+" 至 "+r.endDate);return Api.ok(view(r));
    }
    @PostMapping("/correction-requests") @Transactional Object createCorrection(@RequestBody Map<String,Object> body) {
        attendance.guard();Long employeeId=auth.employee();var r=new CorrectionRequest();r.employeeId=employeeId;r.userId=auth.current().id;r.date=LocalDate.parse(Api.required(body,"date",10));r.type=type(Api.required(body,"type",10));r.requestedTime=LocalTime.parse(Api.required(body,"requestedTime",20));r.reason=Api.required(body,"reason",1000);
        if(r.date.isBefore(attendance.today().minusDays(7))||!r.date.isBefore(attendance.today())||!attendance.workday(r.date)||!attendance.employed(store.get(Employee.class,employeeId),r.date))throw Api.error(400,"INVALID_CORRECTION_DATE","仅可补最近7个已结束自然日中在职的工作日");
        if(!AttendanceEngine.inWindow(attendance.rule(r.date),r.type,r.requestedTime))throw Api.error(400,"OUTSIDE_WINDOW","补卡时间须在对应打卡窗口");
        if(attendance.leave(employeeId,r.date))throw Api.error(409,"LEAVE_CONFLICT","已批准请假日期不能补卡");
        r.pendingKey=employeeId+":"+r.date+":"+r.type;if(store.first(CorrectionRequest.class,"where e.pendingKey=?1",r.pendingKey).isPresent())throw Api.error(409,"DUPLICATE_REQUEST","此日期和打卡类型已有待审批申请");
        store.save(r);attendance.audit(r.userId,"CREATE_CORRECTION",r.id.toString(),r.date+" "+r.type);return Api.ok(view(r));
    }
    @PostMapping("/leave-requests/{id}/cancel") @Transactional Object cancelLeave(@PathVariable Long id) {attendance.guard();var r=store.get(LeaveRequest.class,id);owned(r.userId);pending(r.status);r.status="CANCELLED";attendance.audit(auth.current().id,"CANCEL_LEAVE",id.toString(),"");return Api.ok(view(r));}
    @PostMapping("/correction-requests/{id}/cancel") @Transactional Object cancelCorrection(@PathVariable Long id) {attendance.guard();var r=store.get(CorrectionRequest.class,id);owned(r.userId);pending(r.status);r.status="CANCELLED";r.pendingKey=null;attendance.audit(auth.current().id,"CANCEL_CORRECTION",id.toString(),"");return Api.ok(view(r));}
    @PostMapping("/leave-requests/{id}/review") @Transactional Object reviewLeave(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        var reviewer=auth.admin();attendance.guard();var r=store.get(LeaveRequest.class,id);pending(r.status);other(reviewer,r.userId);boolean approved=Api.bool(body,"approved",false);
        if(approved) for(LocalDate d=r.startDate;!d.isAfter(r.endDate);d=d.plusDays(1)) {
            if(!attendance.events(r.employeeId,d).isEmpty()||!attendance.corrections(r.employeeId,d).isEmpty())throw Api.error(409,"PUNCH_CONFLICT","请假日期已有打卡或已批准补卡，请先核查记录");
        }
        r.status=approved?"APPROVED":"REJECTED";r.reviewerId=reviewer.id;r.comment=comment(body);store.flush();
        if(approved)for(LocalDate d=r.startDate;!d.isAfter(r.endDate)&&!d.isAfter(attendance.today());d=d.plusDays(1))attendance.recalculate(store.get(Employee.class,r.employeeId),d);
        attendance.audit(reviewer.id,"REVIEW_LEAVE",id.toString(),r.status);return Api.ok(view(r));
    }
    @PostMapping("/correction-requests/{id}/review") @Transactional Object reviewCorrection(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        var reviewer=auth.admin();attendance.guard();var r=store.get(CorrectionRequest.class,id);pending(r.status);other(reviewer,r.userId);boolean approved=Api.bool(body,"approved",false);
        if(approved&&attendance.leave(r.employeeId,r.date))throw Api.error(409,"LEAVE_CONFLICT","该日已有批准请假");
        r.status=approved?"APPROVED":"REJECTED";r.pendingKey=null;r.reviewerId=reviewer.id;r.comment=comment(body);store.flush();
        if(approved)attendance.recalculate(store.get(Employee.class,r.employeeId),r.date);
        attendance.audit(reviewer.id,"REVIEW_CORRECTION",id.toString(),r.status);return Api.ok(view(r));
    }
    static String type(String t) {if(!Set.of("IN","OUT").contains(t))throw Api.error(400,"INVALID_TYPE","打卡类型须为IN或OUT");return t;}
    void pending(String status) {if(!"PENDING".equals(status))throw Api.error(409,"ALREADY_REVIEWED","申请已处理，请刷新");}
    void owned(Long userId) {if(!auth.current().id.equals(userId))throw Api.error(403,"FORBIDDEN","只能撤销自己的申请");}
    void other(UserAccount reviewer,Long userId) {if(reviewer.id.equals(userId))throw Api.error(403,"SELF_APPROVAL","管理员不能审批自己的申请，请另一名管理员处理");}
    String comment(Map<String,Object> body) {String c=body.getOrDefault("comment","").toString();if(c.length()>255)throw Api.error(400,"INVALID_INPUT","审批意见最多255字符");return c;}
    Map<String,Object> view(LeaveRequest r) {var m=base(r,r.employeeId,r.userId,r.status);m.put("startDate",r.startDate);m.put("endDate",r.endDate);m.put("type",r.type);m.put("reason",r.reason);m.put("reviewerId",r.reviewerId);m.put("comment",r.comment);return m;}
    Map<String,Object> view(CorrectionRequest r) {var m=base(r,r.employeeId,r.userId,r.status);m.put("date",r.date);m.put("type",r.type);m.put("requestedTime",r.requestedTime);m.put("reason",r.reason);m.put("reviewerId",r.reviewerId);m.put("comment",r.comment);return m;}
    Map<String,Object> base(Domain.Row r,Long employeeId,Long userId,String status) {var e=store.get(Employee.class,employeeId);var m=new LinkedHashMap<String,Object>();m.put("id",r.id);m.put("employeeId",employeeId);m.put("userId",userId);m.put("employeeName",e.name);m.put("employeeNo",e.employeeNo);m.put("status",status);m.put("createdAt",r.createdAt);m.put("version",r.version);return m;}
}
