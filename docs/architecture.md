# 系统架构与数据关系

```mermaid
flowchart LR
    Browser[管理员 / 员工 / 授权打卡浏览器] -->|HTTPS 443| Nginx[Nginx 静态页和反向代理]
    Nginx --> Frontend[Vue 3 页面]
    Nginx -->|HTTP / WebSocket| Java[Spring Boot 127.0.0.1:8080]
    Java -->|事务 / Flyway| DB[(MySQL 8.4)]
    Java -->|内部密钥 / 解密后的有效模板| Python[FastAPI 127.0.0.1:8000]
    Python --> Insight[InsightFace / ONNX Runtime]
    Python --> Motion[MediaPipe Face Landmarker]
```

浏览器仅访问Java业务API。身份、动作通过结果、打卡接受时间、配置/人员库版本均由服务端复核。Python不读取业务数据库；Java以AES-GCM保存模板，内部同步时才解密。相机图像与动作视频不作为持久业务数据保存。

```mermaid
erDiagram
    DEPARTMENT ||--o{ EMPLOYEE : contains
    EMPLOYEE ||--o{ SYS_USER : account
    EMPLOYEE ||--o{ FACE_TEMPLATE : enrolled
    EMPLOYEE ||--o{ PUNCH_EVENT : punches
    EMPLOYEE ||--o{ ATTENDANCE_DAILY : reports
    EMPLOYEE ||--o{ LEAVE_REQUEST : requests
    EMPLOYEE ||--o{ CORRECTION_REQUEST : requests
    SYS_USER ||--o{ LEAVE_REQUEST : submits
    SYS_USER ||--o{ CORRECTION_REQUEST : submits
    PUNCH_DEVICE ||--o{ PUNCH_SESSION : starts
    PUNCH_SESSION ||--o| PUNCH_EVENT : consumed_once
    ATTENDANCE_RULE ||--o{ ATTENDANCE_DAILY : snapshot
    DEPARTMENT {
        bigint id PK
        string name UK
        boolean active
    }
    EMPLOYEE {
        bigint id PK
        string employee_no UK
        bigint department_id FK
        date start_date
        date end_date
        boolean active
    }
    SYS_USER {
        bigint id PK
        string username UK
        string password_hash
        string role
        bigint employee_id FK
        boolean must_change_password
    }
    FACE_TEMPLATE {
        bigint id PK
        bigint employee_id FK
        string encrypted_vector
        string model_version
        string batch
        boolean consent
    }
    PUNCH_DEVICE {
        bigint id PK
        string token_hash UK
        timestamp expires_at
        boolean revoked
    }
    PUNCH_SESSION {
        bigint id PK
        string session_id UK
        bigint device_id FK
        string actions
        bigint config_version
        bigint gallery_version
        timestamp expires_at
        string status
    }
    PUNCH_EVENT {
        bigint id PK
        bigint employee_id FK
        string session_id UK
        timestamp accepted_at
        string source
        boolean liveness_required
        string liveness_status
    }
    ATTENDANCE_DAILY {
        bigint id PK
        bigint employee_id FK
        date date
        timestamp check_in
        timestamp check_out
        string status
        boolean late
        boolean early
        bigint rule_id FK
        string rule_snapshot
    }
    ATTENDANCE_RULE {
        bigint id PK
        date effective_date UK
        time start_time
        time end_time
        int late_grace_minutes
        int early_grace_minutes
    }
    WORK_CALENDAR {
        bigint id PK
        date date UK
        boolean workday
        string note
    }
    LEAVE_REQUEST {
        bigint id PK
        bigint employee_id FK
        bigint user_id FK
        date start_date
        date end_date
        string status
        bigint reviewer_id
    }
    CORRECTION_REQUEST {
        bigint id PK
        bigint employee_id FK
        bigint user_id FK
        date date
        string type
        time requested_time
        string pending_key UK
        string status
    }
    SYSTEM_SETTING {
        bigint id PK
        boolean liveness_enabled
        double recognition_threshold
        double candidate_margin
        boolean calibration_confirmed
        bigint config_version
        bigint gallery_version
    }
    AUDIT_LOG {
        bigint id PK
        bigint actor_id
        string action
        string target
        string detail
    }
```

关系图展示业务关系；数据库外键与具体字段以 `backend/src/main/resources/db/migration/V1__initial_schema.sql` 为准。所有表均有创建/更新时间和版本字段；日报对员工与日期建立联合唯一约束，成功事件对会话编号建立唯一约束，待处理补卡通过唯一键和事务限制重复申请。

考勤日报是原始事件、审批产生的人工修正、请假、规则快照和工作日历的汇总结果。规则从次日生效，工作日历仅允许设置未来例外；历史事件不因批准补卡被覆盖。

事务连接使用 `READ COMMITTED`，并在业务写入前持有系统设置行的悲观锁，以适应100人以内的单机规模。这样等待锁后的查询可以读取前一个事务已经提交的记录，避免重叠请假、重复事件等检查使用旧快照。MySQL默认 `REPEATABLE READ` 的普通查询保留首次读取时的快照，不能仅凭后续拿到写锁就假设其他查询自动刷新，参见[MySQL一致性读取说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html)。生产并发仍须在MySQL8.4进行独立验收。

