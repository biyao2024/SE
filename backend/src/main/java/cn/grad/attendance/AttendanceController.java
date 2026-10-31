package cn.grad.attendance;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private final AttendanceService service;private final AuthController auth;
    public AttendanceController(AttendanceService service,AuthController auth) {this.service=service;this.auth=auth;}
    private List<Map<String,Object>> rows(LocalDate from,LocalDate to,Long employeeId,Long departmentId,String status) {var u=auth.current();Long own="ADMIN".equals(u.role)?null:auth.employee();if(own!=null&&employeeId!=null&&!own.equals(employeeId))throw Api.error(403,"FORBIDDEN","仅可查看自己的考勤");return service.query(own,from==null?service.today().withDayOfMonth(1):from,to==null?service.today():to,employeeId,departmentId,status);}
    @GetMapping Object list(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) Long employeeId,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return Api.ok(Api.page(rows(from,to,employeeId,departmentId,status),page,size));}
    @GetMapping("/summary") Object summary(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) Long employeeId,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String status) {return Api.ok(service.summarize(rows(from,to,employeeId,departmentId,status)));}
    @GetMapping("/export") ResponseEntity<byte[]> export(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) Long employeeId,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String status) throws Exception {
        var data=rows(from,to,employeeId,departmentId,status);
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var detail=book.createSheet("考勤明细");write(detail,0,List.of("统计范围",String.valueOf(from==null?service.today().withDayOfMonth(1):from),String.valueOf(to==null?service.today():to),"导出时间",service.now().toString()));
            write(detail,1,List.of("日期","工号","姓名","部门","签到","签退","日状态","迟到","早退","应出勤","批准补卡次数"));
            String[] keys={"date","employeeNo","employeeName","departmentName","checkIn","checkOut","status","late","early","required","correctionCount"};int i=2;
            for(var r:data){var values=new ArrayList<Object>();for(String k:keys)values.add(r.get(k));write(detail,i++,values);}
            var summary=book.createSheet("月度汇总");write(summary,0,List.of("月份","工号","姓名","应出勤天数","完成打卡天数","迟到次数","早退次数","缺卡天数","缺勤天数","请假天数","批准补卡次数"));
            var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var r:data)grouped.computeIfAbsent(r.get("date").toString().substring(0,7)+"|"+r.get("employeeId"),k->new ArrayList<>()).add(r);
            i=1;for(var g:grouped.entrySet()){var first=g.getValue().getFirst();var stats=service.summarize(g.getValue());write(summary,i++,List.of(g.getKey().split("\\|")[0],first.get("employeeNo"),first.get("employeeName"),stats.get("requiredDays"),stats.get("completedDays"),stats.get("lateCount"),stats.get("earlyCount"),stats.get("missingDays"),stats.get("absentDays"),stats.get("leaveDays"),stats.get("correctionCount")));}
            write(summary,i+1,List.of("指标说明","完成打卡可包含迟到早退；缺勤、缺卡、请假互斥；应出勤按工作日历与在职区间统计；不计算加班。"));
            for(var sheet:book){sheet.createFreezePane(0,sheet==detail?2:1);for(int col=0;col<11;col++)sheet.setColumnWidth(col,(col==2?20:24)*256);}
            book.write(output);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
    }
    private void write(Sheet sheet,int index,List<?> values) {Row row=sheet.createRow(index);for(int i=0;i<values.size();i++){var cell=row.createCell(i);Object v=values.get(i);if(v instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(v==null?"":v.toString());}}
}
