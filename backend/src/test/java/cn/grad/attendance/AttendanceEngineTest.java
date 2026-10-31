package cn.grad.attendance;

import static cn.grad.attendance.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttendanceEngineTest {
    final LocalDate day=LocalDate.of(2026,10,21);
    final Rule rule=new Rule();
    PunchEvent event(String type,String time){var e=new PunchEvent();e.type=type;e.acceptedAt=day.atTime(LocalTime.parse(time));return e;}
    @Test void inclusiveGraceBoundaries(){var result=AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(event("IN","09:05:00"),event("OUT","17:55:00")),List.of());assertThat(result.late()).isFalse();assertThat(result.early()).isFalse();assertThat(result.status()).isEqualTo("NORMAL");}
    @Test void oneSecondOutsideGraceCanBeLateAndEarly(){var result=AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(event("IN","09:05:01"),event("OUT","17:54:59")),List.of());assertThat(result.late()).isTrue();assertThat(result.early()).isTrue();}
    @Test void firstInLastOut(){var result=AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(event("IN","09:00"),event("IN","10:00"),event("OUT","17:00"),event("OUT","18:00")),List.of());assertThat(result.checkIn()).isEqualTo(day.atTime(9,0));assertThat(result.checkOut()).isEqualTo(day.atTime(18,0));}
    @Test void pendingUntilDayEnds(){assertThat(AttendanceEngine.calculate(day,day,rule,true,true,false,List.of(),List.of()).status()).isEqualTo("PENDING");assertThat(AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(),List.of()).status()).isEqualTo("ABSENT");}
    @Test void singlePunchProducesCorrectMissingState(){assertThat(AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(event("IN","09:00")),List.of()).status()).isEqualTo("MISSING_OUT");assertThat(AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(event("OUT","18:00")),List.of()).status()).isEqualTo("MISSING_IN");}
    @Test void leaveAndNonWorkingDaysDoNotBecomeAbsence(){assertThat(AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,true,List.of(),List.of()).status()).isEqualTo("LEAVE");var rest=AttendanceEngine.calculate(day,day.plusDays(1),rule,false,true,false,List.of(),List.of());assertThat(rest.status()).isEqualTo("REST");assertThat(rest.required()).isFalse();assertThat(AttendanceEngine.calculate(day,day.plusDays(1),rule,true,false,false,List.of(),List.of()).required()).isFalse();}
    @Test void correctionOverridesWithoutMutatingRawEvent(){var raw=event("IN","10:00");var fix=new CorrectionRequest();fix.id=1L;fix.updatedAt=day.plusDays(1).atStartOfDay();fix.type="IN";fix.requestedTime=LocalTime.of(9,0);var result=AttendanceEngine.calculate(day,day.plusDays(1),rule,true,true,false,List.of(raw),List.of(fix));assertThat(result.checkIn()).isEqualTo(day.atTime(9,0));assertThat(raw.acceptedAt).isEqualTo(day.atTime(10,0));}
    @Test void punchWindowsIncludeEndpoints(){assertThat(AttendanceEngine.inWindow(rule,"IN",LocalTime.of(6,0))).isTrue();assertThat(AttendanceEngine.inWindow(rule,"IN",LocalTime.of(11,59,59))).isTrue();assertThat(AttendanceEngine.inWindow(rule,"IN",LocalTime.NOON)).isFalse();assertThat(AttendanceEngine.inWindow(rule,"OUT",LocalTime.NOON)).isTrue();assertThat(AttendanceEngine.inWindow(rule,"OUT",LocalTime.of(23,59,59))).isTrue();}
}
