<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus, Search, Camera } from '@element-plus/icons-vue'
import { api, post, put, remove, items, query, errorText } from '../api'
import { useLoad, today } from '../composables'
import { ElMessage, ElMessageBox } from 'element-plus'
import FaceEnrollment from '../components/FaceEnrollment.vue'
const rows=ref<any[]>([]),departments=ref<any[]>([]),total=ref(0),page=ref(1),search=ref(''),departmentId=ref<number|undefined>(),dialog=ref(false),editing=ref<number|null>(null),saving=ref(false),formError=ref(''),faceOpen=ref(false),selected=ref<any>(null),departmentOpen=ref(false),initialPassword=ref(''),credentialOpen=ref(false),createdUsername=ref('')
const blank=()=>({employeeNo:'',name:'',departmentId:undefined as number|undefined,active:true,startDate:today(),endDate:'',username:'',password:'',role:'EMPLOYEE'})
const form=reactive(blank()),departmentName=ref(''),departmentSaving=ref(false)
const {loading,error,load}=useLoad(async()=>{const data=await api('/employees?'+query({page:page.value-1,size:20,keyword:search.value,departmentId:departmentId.value}));rows.value=items(data);total.value=data.total??rows.value.length})
async function loadDepartments(){departments.value=items(await api('/departments?size=200'))}
onMounted(()=>loadDepartments().catch(e=>ElMessage.error(errorText(e))))
function edit(row?:any){editing.value=row?.id??null;Object.assign(form,blank(),row?{employeeNo:row.employeeNo,name:row.name,departmentId:row.departmentId,active:row.active,startDate:row.startDate,endDate:row.endDate||''}:{});formError.value='';dialog.value=true}
async function save(){if(!form.employeeNo.trim()||!form.name.trim()||!form.departmentId||!form.startDate){formError.value='请填写工号、姓名、部门和入职日期';return}if(form.endDate&&form.endDate<form.startDate){formError.value='离职日期不能早于入职日期';return}if(!editing.value&&form.password&&form.password.length<12){formError.value='指定初始密码至少需要12位';return}saving.value=true;formError.value='';try{const payload={...form,endDate:form.endDate||null,username:form.username||undefined,password:form.password||undefined};const data=editing.value?await put(`/employees/${editing.value}`,payload):await post('/employees',payload);dialog.value=false;if(data.initialPassword){initialPassword.value=data.initialPassword;createdUsername.value=data.username||form.username||form.employeeNo;credentialOpen.value=true}ElMessage.success(editing.value?'员工信息已更新':'员工档案及账号已创建');await load()}catch(e){formError.value=errorText(e)}finally{saving.value=false}}
function enroll(row:any){selected.value=row;faceOpen.value=true}
async function deleteFace(row:any){try{await ElMessageBox.confirm(`删除 ${row.name} 的人脸模板后，该员工将无法刷脸打卡。历史考勤记录仍保留。`,'删除人脸模板',{type:'warning',confirmButtonText:'删除模板',cancelButtonText:'取消'});await remove(`/employees/${row.id}/face`);ElMessage.success('人脸模板已删除');load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
async function addDepartment(){if(!departmentName.value.trim())return;departmentSaving.value=true;try{await post('/departments',{name:departmentName.value.trim(),active:true});departmentName.value='';await loadDepartments();ElMessage.success('部门已创建')}catch(e){ElMessage.error(errorText(e))}finally{departmentSaving.value=false}}
async function editDepartment(row:any){try{const {value}=await ElMessageBox.prompt('请输入新的部门名称','修改部门',{inputValue:row.name,inputValidator:v=>!!v?.trim()||'部门名称不能为空',confirmButtonText:'保存',cancelButtonText:'取消'});await put(`/departments/${row.id}`,{name:value.trim(),active:row.active});await loadDepartments();await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
async function toggleDepartment(row:any){try{await ElMessageBox.confirm(`确认${row.active?'停用':'启用'}「${row.name}」？`,'更新部门状态',{confirmButtonText:'确认',cancelButtonText:'取消'});await put(`/departments/${row.id}`,{name:row.name,active:!row.active});await loadDepartments()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(errorText(e))}}
</script>

<template>
  <div class="people-page">
    <header class="page-head people-heading">
      <div><h1>人员与部门</h1><p>员工档案、组织归属与人脸录入</p></div>
      <el-button type="primary" :icon="Plus" @click="edit()">新建员工</el-button>
    </header>

    <div class="directory-layout">
      <aside class="department-nav" aria-label="按部门查看员工">
        <div class="department-nav-title"><h2>部门</h2><span>{{ departments.length }}</span></div>
        <nav class="department-list">
          <button type="button" class="department-option" :class="{ selected: departmentId === undefined }" :aria-pressed="departmentId === undefined" @click="departmentId=undefined;page=1;load()">
            <span class="department-symbol all-departments" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
            <span>全部员工</span>
          </button>
          <button v-for="d in departments" :key="d.id" type="button" class="department-option" :class="{ selected: departmentId === d.id }" :aria-pressed="departmentId === d.id" @click="departmentId=d.id;page=1;load()">
            <span class="department-symbol" aria-hidden="true"></span>
            <span class="department-name">{{ d.name }}</span>
            <small v-if="!d.active">停用</small>
          </button>
        </nav>
        <el-button class="manage-departments" text @click="departmentOpen=true">部门管理 <span aria-hidden="true">↗</span></el-button>
      </aside>

      <section class="directory-surface" aria-label="员工档案">
        <div class="directory-toolbar">
          <div class="directory-title">
            <h2>{{ departments.find(d => d.id === departmentId)?.name || '全部员工' }}</h2>
            <span class="directory-count">{{ loading ? '—' : total }} 人</span>
          </div>
          <div class="directory-search">
            <el-input v-model="search" placeholder="搜索姓名或工号" :prefix-icon="Search" clearable aria-label="搜索员工" @keyup.enter="page=1;load()" />
            <el-button @click="page=1;load()">查询</el-button>
          </div>
        </div>
        <el-alert v-if="error" class="directory-alert" :title="error" type="error" :closable="false" />
        <el-table :data="rows" v-loading="loading" class="directory-table" empty-text="暂无员工档案，点击「新建员工」添加">
          <el-table-column label="员工" min-width="200">
            <template #default="{ row }">
              <div class="employee-identity">
                <span class="employee-avatar" :class="{ inactive: !row.active }">{{ row.name?.slice(0,1) }}</span>
                <div><strong>{{ row.name }}</strong><small>工号 {{ row.employeeNo }}</small></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="部门 / 入职日期" min-width="170">
            <template #default="{ row }"><div class="employee-organization"><span>{{ row.departmentName }}</span><small>{{ row.startDate }} 入职</small></div></template>
          </el-table-column>
          <el-table-column label="在职状态" width="100">
            <template #default="{ row }"><span class="employment-state" :class="{ inactive: !row.active }"><i aria-hidden="true"></i>{{ row.active ? '在职' : '已停用' }}</span></template>
          </el-table-column>
          <el-table-column label="人脸录入" width="130">
            <template #default="{ row }"><span class="enrollment-state" :class="{ enrolled: row.faceEnrolled }"><el-icon><Camera /></el-icon>{{ row.faceEnrolled ? '已录入' : '待录入' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" min-width="245" fixed="right" align="right">
            <template #default="{ row }">
              <div class="employee-actions">
                <el-button text size="small" @click="edit(row)">编辑</el-button>
                <el-button class="enroll-button" plain size="small" :disabled="!row.active" @click="enroll(row)">{{ row.faceEnrolled ? '重新录入' : '录入人脸' }}</el-button>
                <el-button v-if="row.faceEnrolled" class="delete-template" text type="danger" size="small" @click="deleteFace(row)">删除模板</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="employee-mobile-list" v-loading="loading" aria-label="员工档案列表">
          <p v-if="!rows.length && !loading" class="mobile-empty">暂无员工档案，点击「新建员工」添加</p>
          <article v-for="row in rows" :key="row.id" class="employee-card">
            <header class="employee-card-header">
              <div class="employee-identity">
                <span class="employee-avatar" :class="{ inactive: !row.active }">{{ row.name?.slice(0,1) }}</span>
                <div><strong>{{ row.name }}</strong><small>工号 {{ row.employeeNo }}</small></div>
              </div>
              <span class="employment-state" :class="{ inactive: !row.active }"><i aria-hidden="true"></i>{{ row.active ? '在职' : '已停用' }}</span>
            </header>
            <dl class="employee-card-details">
              <div><dt>所属部门</dt><dd>{{ row.departmentName }}</dd></div>
              <div><dt>入职日期</dt><dd>{{ row.startDate }}</dd></div>
              <div class="employee-card-face"><dt>人脸录入</dt><dd><span class="enrollment-state" :class="{ enrolled: row.faceEnrolled }"><el-icon><Camera /></el-icon>{{ row.faceEnrolled ? '已录入' : '待录入' }}</span></dd></div>
            </dl>
            <footer class="employee-card-actions">
              <el-button text @click="edit(row)">编辑档案</el-button>
              <el-button class="enroll-button" plain :disabled="!row.active" @click="enroll(row)">{{ row.faceEnrolled ? '重新录入' : '录入人脸' }}</el-button>
              <el-button v-if="row.faceEnrolled" text type="danger" @click="deleteFace(row)">删除模板</el-button>
            </footer>
          </article>
        </div>
        <footer class="directory-footer"><span>每页 20 人</span><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="load" /></footer>
      </section>
    </div>
<el-dialog v-model="dialog" :title="editing?'编辑员工档案':'新建员工档案'" width="620px" :close-on-click-modal="false"><el-alert v-if="formError" :title="formError" type="error" :closable="false"/><el-form label-position="top" @submit.prevent="save"><div class="form-grid"><el-form-item label="工号" required><el-input v-model="form.employeeNo" :disabled="!!editing" maxlength="32"/></el-form-item><el-form-item label="姓名" required><el-input v-model="form.name" maxlength="50"/></el-form-item><el-form-item label="部门" required><el-select v-model="form.departmentId"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" :disabled="!d.active"/></el-select></el-form-item><el-form-item v-if="editing" label="在职状态"><el-switch v-model="form.active" :disabled="rows.find(r=>r.id===editing)?.active===false" active-text="在职" inactive-text="停用"/></el-form-item><el-form-item label="入职日期" required><el-date-picker v-model="form.startDate" :disabled="!!editing" type="date" value-format="YYYY-MM-DD" style="width:100%"/></el-form-item><el-form-item v-if="editing" label="停用日期"><el-input :model-value="form.endDate||'停用时由系统记录当天日期'" disabled/></el-form-item><template v-if="!editing"><el-form-item label="登录账号（留空使用工号）"><el-input v-model="form.username" autocomplete="off" maxlength="64"/></el-form-item><el-form-item label="账号权限"><el-select v-model="form.role"><el-option label="员工" value="EMPLOYEE"/><el-option label="管理员" value="ADMIN"/></el-select></el-form-item><el-form-item label="初始密码（留空自动生成）" class="span-2"><el-input v-model="form.password" type="password" show-password autocomplete="new-password"/><div class="help-text">初始密码仅显示一次，请交给员工本人。首次登录需修改。</div></el-form-item></template></div></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存档案</el-button></template></el-dialog><FaceEnrollment v-model="faceOpen" :employee="selected" @saved="load"/><el-dialog v-model="credentialOpen" title="员工账号已创建" width="440px" :close-on-click-modal="false" @closed="initialPassword='' "><p class="help-text">请将账号和初始密码交给员工本人，关闭后无法再次查看。</p><el-descriptions :column="1" border><el-descriptions-item label="账号">{{createdUsername}}</el-descriptions-item><el-descriptions-item label="初始密码"><code>{{initialPassword}}</code></el-descriptions-item></el-descriptions><template #footer><el-button type="primary" @click="credentialOpen=false">已记录</el-button></template></el-dialog><el-dialog v-model="departmentOpen" title="部门管理" width="600px"><div class="toolbar"><el-input v-model="departmentName" placeholder="新部门名称" maxlength="50" style="flex:1" @keyup.enter="addDepartment"/><el-button type="primary" :loading="departmentSaving" @click="addDepartment">添加部门</el-button></div><el-table :data="departments"><el-table-column prop="name" label="部门名称"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.active?'success':'info'">{{row.active?'启用':'停用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="150"><template #default="{row}"><el-button text type="primary" size="small" @click="editDepartment(row)">修改</el-button><el-button text size="small" @click="toggleDepartment(row)">{{row.active?'停用':'启用'}}</el-button></template></el-table-column></el-table></el-dialog>
  </div>
</template>

<style scoped>
.people-heading { margin-bottom: 27px; align-items: flex-end; }
.people-heading h1 { font-size: 28px; letter-spacing: -.7px; }
.people-heading p { margin-top: 9px; color: #6e785f; }
.directory-layout { display: grid; grid-template-columns: 192px minmax(0, 1fr); gap: 22px; align-items: start; }
.department-nav { padding: 17px 0; border-top: 1px solid #dce2d4; border-bottom: 1px solid #dce2d4; min-width: 0; }
.department-nav-title { display: flex; justify-content: space-between; align-items: center; padding: 0 13px 17px; }
.department-nav-title h2 { margin: 0; font-size: 12px; font-weight: 600; color: #6e785f; }
.department-nav-title > span { color: #6e785f; font-size: 11px; }
.department-list { display: grid; gap: 5px; }
.department-option { display: flex; align-items: center; gap: 11px; width: 100%; padding: 12px 13px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: #6e785f; text-align: left; font-size: 12px; line-height: 1.5; cursor: pointer; transition: background .15s, color .15s; }
.department-option:hover { background: #eef1e8; color: #485b3c; }
.department-option.selected { background: #e8efdf; border-color: #dce6d2; color: #405a33; font-weight: 500; }
.department-option small { margin-left: auto; color: #6e785f; font-size: 10px; font-weight: 400; }
.department-name { overflow-wrap: anywhere; }
.department-symbol { flex-shrink: 0; width: 12px; height: 12px; border: 1px solid currentColor; border-radius: 3px; opacity: .65; position: relative; }
.department-symbol:not(.all-departments)::after { content: ''; position: absolute; top: 3px; left: 3px; width: 4px; height: 4px; border: 1px solid currentColor; }
.all-departments { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; border: none; }
.all-departments i { border: 1px solid currentColor; border-radius: 1px; }
.manage-departments { display: flex; justify-content: space-between; width: 100%; margin: 18px 0 0; padding: 10px 13px; color: #6e785f; font-size: 11px; }
.manage-departments span { margin-left: 9px; font-size: 15px; }
.directory-surface { min-width: 0; overflow: hidden; border: 1px solid #e1e6d9; background: #fffefa; border-radius: 12px; }
.directory-toolbar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 20px; padding: 22px 23px; }
.directory-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.directory-title h2 { margin: 0; color: #3e4b36; font-size: 15px; font-weight: 600; }
.directory-count { padding: 3px 7px; border-radius: 4px; color: #6e785f; background: #f0f3ea; font-size: 11px; white-space: nowrap; }
.directory-search { display: flex; align-items: center; gap: 9px; width: 290px; max-width: 100%; }
.directory-search :deep(.el-input) { min-width: 0; }
.directory-search .el-button { flex-shrink: 0; }
.directory-alert { margin: 0 22px 16px; width: auto; }
.directory-table { --el-table-header-bg-color: #f7f9f3; --el-table-tr-bg-color: #fffefa; --el-table-row-hover-bg-color: #f5f8f0; }
.directory-table :deep(th.el-table__cell) { height: 43px; font-size: 11px; border-top: 1px solid #e9eddf; }
.directory-table :deep(td.el-table__cell) { height: 88px; }
.directory-table :deep(th:first-child .cell), .directory-table :deep(td:first-child .cell) { padding-left: 23px; }
.directory-table :deep(th:last-child .cell), .directory-table :deep(td:last-child .cell) { padding-right: 19px; }
.employee-identity { display: flex; align-items: center; gap: 13px; }
.employee-avatar { flex-shrink: 0; width: 40px; height: 42px; display: grid; place-items: center; border-radius: 11px; border: 1px solid #dce5d1; background: #eaf0e1; color: #61784d; font-size: 16px; }
.employee-avatar.inactive { background: #f0f1ec; border-color: #e6e8e0; color: #9a9e91; }
.employee-identity strong { font-size: 14px; font-weight: 500; color: #3c4835; }
.employee-identity small, .employee-organization small { display: block; margin-top: 6px; font-size: 10px; color: #6e785f; font-variant-numeric: tabular-nums; }
.employee-organization { font-size: 12px; color: #6e785f; }
.employment-state { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #5d7851; }
.employment-state i { width: 5px; height: 5px; border-radius: 50%; background: #7a996a; }
.employment-state.inactive { color: #6e785f; }
.employment-state.inactive i { background: #b7bcae; }
.enrollment-state { display: inline-flex; align-items: center; gap: 6px; padding: 4px 7px; border: 1px dashed #dedfce; border-radius: 5px; font-size: 11px; line-height: 1.4; color: #85714e; }
.enrollment-state.enrolled { background: #f0f4e9; border: 1px solid #e3eada; color: #6b7e5a; }
.enrollment-state .el-icon { font-size: 13px; }
.employee-actions { display: flex; justify-content: flex-end; align-items: center; gap: 7px; white-space: nowrap; }
.employee-actions .el-button { margin: 0; font-size: 11px; padding-left: 7px; padding-right: 7px; }
.enroll-button { border-color: #dce4d2; color: #5f7750; background: #f9fbf5; }
.employee-actions .delete-template { color: #926e60; }
.employee-actions .delete-template:hover { color: #ab5c46; }
.directory-footer { display: flex; justify-content: space-between; align-items: center; padding: 19px 23px; font-size: 11px; color: #6e785f; }
@media (max-width: 1180px) {
  .directory-layout { grid-template-columns: 1fr; gap: 17px; }
  .department-nav { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; padding: 12px 0; border-bottom: 0; }
  .department-nav-title { padding: 0 0 0 3px; gap: 8px; }
  .department-list { display: flex; flex: 1; flex-wrap: wrap; gap: 5px; }
  .department-option { width: auto; padding: 8px 10px; gap: 7px; }
  .department-symbol { display: none; }
  .manage-departments { width: auto; margin: 0; padding: 8px 0; }
}
@media (max-width: 800px) {
  .people-heading { align-items: flex-start; }
  .directory-toolbar { padding: 20px 17px; gap: 15px; }
  .directory-search { width: 100%; }
  .department-nav-title { display: none; }
  .department-list { flex-basis: 100%; }
  .manage-departments { padding-left: 3px; }
  .directory-footer { padding: 16px 17px; gap: 12px; }
}

.employee-mobile-list { display: none; }
@media (max-width: 600px) {
  .directory-table { display: none; }
  .employee-mobile-list { display: block; padding: 0 14px 5px; min-height: 110px; }
  .employee-card { margin-bottom: 12px; border: 1px solid #e1e7d8; border-radius: 10px; overflow: hidden; background: #fffefa; }
  .employee-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 17px 16px 0; }
  .employee-card .employee-identity { min-width: 0; flex: 1; gap: 11px; }
  .employee-card .employee-identity > div { min-width: 0; overflow-wrap: anywhere; }
  .employee-card .employee-identity strong { font-size: 15px; }
  .employee-card .employee-identity small { font-size: 11px; line-height: 1.5; }
  .employee-card .employment-state { flex-shrink: 0; font-size: 11px; }
  .employee-card-details { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; margin: 20px 0 0; padding: 0 16px 17px; }
  .employee-card-details dt { font-size: 10px; color: #748069; margin-bottom: 7px; }
  .employee-card-details dd { margin: 0; font-size: 12px; color: #42543a; line-height: 1.6; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
  .employee-card-details .employee-card-face { grid-column: 1 / -1; display: flex; align-items: center; gap: 12px; }
  .employee-card-face dt { margin: 0; }
  .employee-card-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px; padding: 10px 12px; border-top: 1px solid #edf0e6; background: #f8faf4; }
  .employee-card-actions .el-button { margin: 0; font-size: 11px; padding: 8px 9px; height: 36px; }
  .employee-card-actions .el-button--danger { color: #926e60; }
  .mobile-empty { margin: 0; padding: 38px 18px; text-align: center; font-size: 12px; color: #748069; line-height: 1.8; }
  .directory-footer { flex-direction: column; align-items: flex-start; padding-top: 12px; }
  .directory-footer :deep(.el-pagination) { max-width: 100%; overflow-x: auto; }
}
</style>
