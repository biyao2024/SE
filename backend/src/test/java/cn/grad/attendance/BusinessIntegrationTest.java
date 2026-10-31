package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:attendance-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","app.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.internal-key=test-only-internal-key-never-use-in-deployment-12345","app.bootstrap-password=TestPassword123!","app.secure-cookie=false"})
@AutoConfigureMockMvc @Transactional
class BusinessIntegrationTest {
    @TestConfiguration static class Time { @Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-10-22T01:05:00Z"),ZoneId.of("Asia/Shanghai"));} }
    @Autowired MockMvc mvc;@Autowired Store store;@Autowired AttendanceService attendance;@Autowired KioskService kiosk;@Autowired Crypto crypto;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean AlgorithmClient algorithm;
    @BeforeEach void setup(){var admin=store.first(UserAccount.class,"where e.username='admin'").orElseThrow();admin.mustChangePassword=false;when(algorithm.health()).thenReturn(Map.of("ready",true,"galleryVersion",attendance.settings().galleryVersion,"modelVersion","test-model","detail",""));}
    Employee employee(String no){var e=new Employee();e.employeeNo=no;e.name="测试员工";e.departmentId=store.list(Department.class,"").getFirst().id;e.startDate=attendance.today().minusDays(10);store.save(e);var u=new UserAccount();u.username=no;u.employeeId=e.id;u.passwordHash=encoder.encode("EmployeePass123!");u.role="EMPLOYEE";u.mustChangePassword=false;store.save(u);return e;}
    ResultActions postJson(String path,Object body,String user,String role)throws Exception{return mvc.perform(post(path).with(user(user).roles(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));}
    @Test void authAndCsrfAreEnforced()throws Exception{mvc.perform(get("/api/v1/employees")).andExpect(status().isUnauthorized());mvc.perform(post("/api/v1/departments").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"test\"}")).andExpect(status().isForbidden());mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());}
    @Test void employeeCannotReadOthersOrAdminEndpoints()throws Exception{var a=employee("E001");var b=employee("E002");mvc.perform(get("/api/v1/employees").with(user("E001").roles("EMPLOYEE"))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/attendance").param("employeeId",b.id.toString()).with(user("E001").roles("EMPLOYEE"))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/attendance").with(user("E001").roles("EMPLOYEE"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11));}
    @Test void initialPasswordMustChangeAndDisabledSessionCannotAccess()throws Exception{var e=employee("E003");var u=store.first(UserAccount.class,"where e.employeeId=?1",e.id).orElseThrow();u.mustChangePassword=true;mvc.perform(get("/api/v1/attendance").with(user("E003").roles("EMPLOYEE"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));u.mustChangePassword=false;e.active=false;mvc.perform(get("/api/v1/attendance").with(user("E003").roles("EMPLOYEE"))).andExpect(status().isUnauthorized());}
    @Test void unauthorizedDeviceAndUnavailableAlgorithmNeverCreateEvents()throws Exception{mvc.perform(post("/api/v1/kiosk/sessions").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"IN\"}")).andExpect(status().isForbidden());var d=device();attendance.settings().calibrationConfirmed=true;when(algorithm.health()).thenReturn(Map.of("ready",false,"galleryVersion",-1));assertThatThrownBy(()->kiosk.create("test-device","IN")).isInstanceOf(Api.Failure.class).hasMessageContaining("未就绪");assertThat(store.list(PunchEvent.class,"")).isEmpty();}
    Device device(){var d=new Device();d.name="测试设备";d.tokenHash=Crypto.hash("test-device");d.expiresAt=attendance.now().plusDays(7);store.save(d);return d;}
    @Test void createsKioskSessionThroughSpringProxiesWithOffsetExpiryAndPersistedState()throws Exception{
        assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(attendance)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(kiosk)).isTrue();
        var d=device();var settings=attendance.settings();settings.calibrationConfirmed=true;settings.livenessEnabled=true;
        var response=mvc.perform(post("/api/v1/kiosk/sessions").with(csrf())
            .cookie(new jakarta.servlet.http.Cookie("PUNCH_DEVICE","test-device"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"IN\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var data=json.readTree(response.getContentAsString()).get("data");
        String sessionId=data.get("sessionId").asText();
        OffsetDateTime expires=OffsetDateTime.parse(data.get("expiresAt").asText());
        assertThat(expires.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(expires.toInstant()).isEqualTo(Instant.parse("2026-10-22T01:06:00Z"));
        assertThat(data.get("livenessRequired").asBoolean()).isTrue();
        var sent=org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(algorithm).createSession(sent.capture());
        var algorithmBody=json.valueToTree(sent.getValue());
        assertThat(algorithmBody.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(algorithmBody.get("expiresAt").asText()).isEqualTo(expires.toString());
        assertThat(algorithmBody.get("galleryVersion").asLong()).isEqualTo(settings.galleryVersion);
        assertThat(algorithmBody.get("configVersion").asLong()).isEqualTo(settings.configVersion);
        assertThat(algorithmBody.get("livenessRequired").asBoolean()).isTrue();
        assertThat(algorithmBody.get("actions")).isEqualTo(data.get("actions"));
        assertThat(json.convertValue(data.get("actions"),List.class)).containsExactlyInAnyOrder("BLINK","MOUTH");
        store.flush();
        assertThat(jdbc.queryForObject("select device_id from punch_session where session_id=?",Long.class,sessionId)).isEqualTo(d.id);
        assertThat(jdbc.queryForObject("select status from punch_session where session_id=?",String.class,sessionId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select expires_at from punch_session where session_id=?",java.sql.Timestamp.class,sessionId).toLocalDateTime()).isEqualTo(expires.toLocalDateTime());
        assertThat(store.list(PunchEvent.class,"")).isEmpty();
    }
    PunchSession session(Device device,Employee employee,String id){var s=new PunchSession();s.sessionId=id;s.deviceId=device.id;s.type="IN";s.actions="BLINK,MOUTH";s.livenessRequired=false;s.configVersion=attendance.settings().configVersion;s.galleryVersion=attendance.settings().galleryVersion;s.expiresAt=attendance.now().plusSeconds(60);store.save(s);if(store.list(FaceTemplate.class,"where e.employeeId=?1",employee.id).isEmpty()){var f=new FaceTemplate();f.employeeId=employee.id;f.modelVersion="test-model";f.batch="test";f.consent=true;f.encryptedVector=crypto.encrypt("[1,0]",employee.id);store.save(f);}return s;}
    Map<String,Object> result(Employee e){return Map.of("employeeId",e.id,"score",0.9,"margin",0.4,"galleryVersion",attendance.settings().galleryVersion,"configVersion",attendance.settings().configVersion,"modelVersion","test-model","livenessStatus","SKIPPED");}
    @Test void sessionConsumptionAndThirtySecondDeduplication() {var e=employee("E004");var d=device();var first=session(d,e,"one");assertThat(kiosk.finish(first.sessionId,d.id,result(e)).get("status")).isEqualTo("SUCCESS");assertThat(kiosk.finish(first.sessionId,d.id,result(e)).get("status")).isEqualTo("SUCCESS");var second=session(d,e,"two");assertThat(kiosk.finish(second.sessionId,d.id,result(e)).get("status")).isEqualTo("DUPLICATE");assertThat(store.list(PunchEvent.class,"where e.employeeId=?1",e.id)).hasSize(1);assertThat(store.list(Daily.class,"where e.employeeId=?1",e.id)).hasSize(1);}
    @Test void staleConfigurationAndRevokedDeviceRejected(){var e=employee("E005");var d=device();var s=session(d,e,"stale");attendance.settings().configVersion++;assertThatThrownBy(()->kiosk.check(s.sessionId,d.id)).hasMessageContaining("变更");d.revoked=true;assertThatThrownBy(()->kiosk.device("test-device")).hasMessageContaining("撤销");}
    @Test void overlappingLeaveAndSelfApprovalAreRejected()throws Exception{var e=employee("E006");var data=Map.of("startDate","2026-10-23","endDate","2026-10-23","type","事假","reason","测试申请");var created=postJson("/api/v1/leave-requests",data,"E006","EMPLOYEE").andExpect(status().isOk()).andReturn();long id=json.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();postJson("/api/v1/leave-requests",data,"E006","EMPLOYEE").andExpect(status().isConflict());var u=store.first(UserAccount.class,"where e.employeeId=?1",e.id).orElseThrow();u.role="ADMIN";postJson("/api/v1/leave-requests/"+id+"/review",Map.of("approved",true),"E006","ADMIN").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_APPROVAL"));}
    @Test void correctionApprovalRetainsRawEventAndRecalculates()throws Exception{var e=employee("E007");LocalDate yesterday=attendance.today().minusDays(1);var event=new PunchEvent();event.employeeId=e.id;event.type="IN";event.acceptedAt=yesterday.atTime(10,0);event.source="FACE";event.livenessStatus="SKIPPED";store.save(event);var data=Map.of("date",yesterday.toString(),"type","IN","requestedTime","09:00","reason","现场核对已到岗");var created=postJson("/api/v1/correction-requests",data,"E007","EMPLOYEE").andExpect(status().isOk()).andReturn();long id=json.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();postJson("/api/v1/correction-requests",data,"E007","EMPLOYEE").andExpect(status().isConflict());postJson("/api/v1/correction-requests/"+id+"/review",Map.of("approved",true),"admin","ADMIN").andExpect(status().isOk());postJson("/api/v1/correction-requests/"+id+"/review",Map.of("approved",true),"admin","ADMIN").andExpect(status().isConflict());var daily=store.first(Daily.class,"where e.employeeId=?1 and e.date=?2",e.id,yesterday).orElseThrow();assertThat(daily.checkIn).isEqualTo(yesterday.atTime(9,0));assertThat(daily.late).isFalse();assertThat(event.acceptedAt).isEqualTo(yesterday.atTime(10,0));}
    @Test void leaveApprovalConflictsWithPunch()throws Exception{var e=employee("E008");var event=new PunchEvent();event.employeeId=e.id;event.type="IN";event.acceptedAt=attendance.now();event.source="FACE";event.livenessStatus="SKIPPED";store.save(event);var r=new LeaveRequest();r.employeeId=e.id;r.userId=store.first(UserAccount.class,"where e.employeeId=?1",e.id).orElseThrow().id;r.startDate=r.endDate=attendance.today();r.type="事假";r.reason="test";store.save(r);postJson("/api/v1/leave-requests/"+r.id+"/review",Map.of("approved",true),"admin","ADMIN").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUNCH_CONFLICT"));}
    @Test void futureRuleDoesNotChangeHistoricalSnapshot()throws Exception{var e=employee("E009");var day=attendance.today().minusDays(1);var old=attendance.recalculate(e,day);String snapshot=old.ruleSnapshot;var body=Map.of("startTime","08:00","endTime","17:00","lateGraceMinutes",0,"earlyGraceMinutes",0,"inWindowStart","06:00","inWindowEnd","11:59:59","outWindowStart","12:00","outWindowEnd","23:59:59");mvc.perform(put("/api/v1/rules").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.effectiveDate").value("2026-10-23"));assertThat(attendance.recalculate(e,day).ruleSnapshot).isEqualTo(snapshot);mvc.perform(put("/api/v1/calendar/2026-10-22").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"workday\":false}")).andExpect(status().isBadRequest());}
    @Test void exportContainsBothSheetsAndSameQueryCount()throws Exception{employee("E010");var response=mvc.perform(get("/api/v1/attendance/export").param("from","2026-10-21").param("to","2026-10-22").with(user("E010").roles("EMPLOYEE"))).andExpect(status().isOk()).andReturn();try(var workbook=new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getResponse().getContentAsByteArray()))){assertThat(workbook.getNumberOfSheets()).isEqualTo(2);assertThat(workbook.getSheet("考勤明细").getLastRowNum()).isEqualTo(3);assertThat(workbook.getSheet("月度汇总").getRow(1).getCell(3).getNumericCellValue()).isEqualTo(2);}}
    @Test void encryptionIsRandomizedAndBoundToEmployee(){String encrypted=crypto.encrypt("[1,2,3]",1L);assertThat(crypto.decrypt(encrypted,1L)).isEqualTo("[1,2,3]");assertThat(crypto.encrypt("[1,2,3]",1L)).isNotEqualTo(encrypted);assertThatThrownBy(()->crypto.decrypt(encrypted,2L)).isInstanceOf(IllegalStateException.class);}
    @Test void requestIdIsConsistentAndCannotBeChosenByClient()throws Exception{var response=mvc.perform(get("/api/v1/health").header("X-Request-ID","untrusted-client-value")).andExpect(status().isOk()).andReturn().getResponse();String id=response.getHeader("X-Request-ID");assertThat(UUID.fromString(id)).isNotNull();assertThat(json.readTree(response.getContentAsString()).get("requestId").asText()).isEqualTo(id);}
    @Test void disabledLoginSessionIsInvalidatedAndAnotherAccountCanLogin()throws Exception{
        var employee=employee("E011");
        var login=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"E011\",\"password\":\"EmployeePass123!\"}")).andExpect(status().isOk()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);assertThat(session).isNotNull();
        employee.active=false;
        mvc.perform(get("/api/v1/attendance").session(session)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andExpect(status().isOk());
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentOverlappingLeaveRequestsOnlyCreateOne()throws Exception{
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->employee("CONCURRENT"));
        var body=Map.of("startDate","2026-10-23","endDate","2026-10-23","type","事假","reason","并发提交");
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Integer> submit=()->{gate.await();return postJson("/api/v1/leave-requests",body,"CONCURRENT","EMPLOYEE").andReturn().getResponse().getStatus();};
            var a=executor.submit(submit);var b=executor.submit(submit);gate.countDown();
            assertThat(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }

    @Test void testPunchesAreStoredButDoNotAffectFormalAttendanceOrDeduplication()throws Exception {
        var e=employee("TEST-MODE");var d=device();var trial=session(d,e,"trial");trial.testMode=true;
        assertThat(kiosk.finish(trial.sessionId,d.id,result(e)).get("status")).isEqualTo("SUCCESS");
        assertThat(store.list(PunchEvent.class,"where e.source='FACE_TEST'")).hasSize(1);
        assertThat(attendance.events(e.id,attendance.today())).isEmpty();
        assertThat(store.list(Daily.class,"where e.employeeId=?1",e.id)).isEmpty();
        var formal=session(d,e,"formal");assertThat(kiosk.finish(formal.sessionId,d.id,result(e)).get("status")).isEqualTo("SUCCESS");
        assertThat(attendance.events(e.id,attendance.today())).hasSize(1);
        mvc.perform(get("/api/v1/attendance/test-events").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].source").value("FACE_TEST"));
        var other=employee("OTHER-TEST");
        mvc.perform(get("/api/v1/attendance/test-events").with(user("OTHER-TEST").roles("EMPLOYEE"))).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void onlyTestModeBypassesTimeWindowsAndModeIsSnapshotted() {
        device();var settings=attendance.settings();settings.calibrationConfirmed=true;
        var rule=attendance.rule(attendance.today());rule.inWindowStart=java.time.LocalTime.of(10,0);rule.inWindowEnd=java.time.LocalTime.of(11,0);
        assertThatThrownBy(()->kiosk.create("test-device","IN")).hasMessageContaining("非打卡时段");
        settings.testMode=true;var created=kiosk.create("test-device","IN");
        assertThat(store.first(PunchSession.class,"where e.sessionId=?1",created.get("sessionId")).orElseThrow().testMode).isTrue();
    }
    @Test void employeesCannotEnableTestMode()throws Exception {
        employee("TEST-EMP");
        mvc.perform(put("/api/v1/settings").with(user("TEST-EMP").roles("EMPLOYEE")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"testMode\":true}")).andExpect(status().isForbidden());
        assertThat(attendance.settings().testMode).isFalse();
    }
    @Test void diagnosticsRequireAdministratorAndEnabledTestMode()throws Exception {
        mvc.perform(post("/api/v1/kiosk/diagnostics/sessions").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"IN\"}")).andExpect(status().isUnauthorized());
        employee("DIAG-EMP");
        postJson("/api/v1/kiosk/diagnostics/sessions",Map.of("type","IN"),"DIAG-EMP","EMPLOYEE").andExpect(status().isForbidden());
        postJson("/api/v1/kiosk/diagnostics/sessions",Map.of("type","IN"),"admin","ADMIN").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEST_MODE_REQUIRED"));
        var settings=attendance.settings();settings.testMode=true;settings.calibrationConfirmed=true;device();
        mvc.perform(post("/api/v1/kiosk/diagnostics/sessions").with(user("admin").roles("ADMIN")).with(csrf()).cookie(new jakarta.servlet.http.Cookie("PUNCH_DEVICE","test-device")).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"IN\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").isNotEmpty());
        var account=store.first(UserAccount.class,"where e.username='admin'").orElseThrow();account.enabled=false;
        assertThatThrownBy(()->kiosk.diagnosticAccess("admin")).hasMessageContaining("有效管理员");
    }
    @Test void issuedDeviceCookieAlsoCoversDiagnosticCreationAndStreaming()throws Exception {
        var response=postJson("/api/v1/devices",Map.of("name","Cookie path regression"),"admin","ADMIN").andExpect(status().isOk()).andReturn().getResponse();
        var cookie=java.net.HttpCookie.parse(response.getHeader("Set-Cookie")).getFirst();
        assertThat(cookie.getName()).isEqualTo("PUNCH_DEVICE");
        assertThat("/api/v1/kiosk/diagnostics/sessions").startsWith(cookie.getPath()+"/");
        assertThat("/api/v1/kiosk/diagnostics/stream").startsWith(cookie.getPath()+"/");
        mvc.perform(get("/api/v1/kiosk/diagnostics/stream")).andExpect(status().isUnauthorized());
        employee("DIAG-COOKIE-EMP");
        mvc.perform(get("/api/v1/kiosk/diagnostics/stream").with(user("DIAG-COOKIE-EMP").roles("EMPLOYEE"))).andExpect(status().isForbidden());
    }
}
