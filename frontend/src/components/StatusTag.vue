<script setup lang="ts">import { statusNames, statusType } from '../composables'; defineProps<{status:string;late?:boolean;early?:boolean;request?:boolean}>()</script>
<template>
  <div class="status-stack">
    <el-tag :type="statusType(status)" class="status-tag"><span class="status-dot" aria-hidden="true" />{{request&&status==='PENDING'?'待审批':statusNames[status]||status}}</el-tag>
    <el-tag v-if="late" type="warning" class="status-tag">迟到</el-tag>
    <el-tag v-if="early" type="warning" class="status-tag">早退</el-tag>
  </div>
</template>

<style scoped>
.status-tag { height: 24px; padding: 0 8px; border-radius: 5px; }
.status-tag :deep(.el-tag__content) { display: inline-flex; align-items: center; gap: 6px; }
.status-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
</style>
