<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { statusNames } from '../composables'

const props = defineProps<{ summary: any }>()
const trend = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
const distribution = computed(() => props.summary?.distribution || [])
const total = computed(() => distribution.value.reduce((sum: number, row: any) => sum + row.value, 0))

function render() {
  if (!chart) return
  const rows = props.summary?.trend || []
  chart.setOption({
    animation: false,
    color: ['#456c5a', '#ae8568'],
    textStyle: { fontFamily: '"Segoe UI", "Noto Sans SC", "Microsoft YaHei", sans-serif' },
    graphic: rows.length ? [] : [{
      type: 'text', left: 'center', top: '40%',
      style: { text: '所选日期暂无记录', fill: '#7b847d', fontSize: 12 },
    }],
    tooltip: {
      trigger: 'axis', backgroundColor: '#fff', borderColor: '#e1e5df', padding: [10, 14],
      textStyle: { color: '#353f37', fontSize: 12 },
    },
    legend: {
      bottom: 0, left: 0, itemWidth: 16, itemHeight: 7, itemGap: 22,
      textStyle: { color: '#626c65', fontSize: 11 },
    },
    grid: { top: 16, right: 12, bottom: 48, left: 30, containLabel: true },
    xAxis: {
      type: 'category', boundaryGap: false, data: rows.map((r: any) => r.date.slice(5)),
      axisLine: { lineStyle: { color: '#e5e8e1' } }, axisTick: { show: false },
      axisLabel: { color: '#7b847d', fontSize: 10, margin: 12 },
    },
    yAxis: {
      type: 'value', minInterval: 1,
      axisLabel: { color: '#7b847d', fontSize: 10 },
      splitLine: { lineStyle: { color: '#edf0e9', type: 'dashed' } },
    },
    series: [
      {
        name: '完成打卡', type: 'line', symbolSize: 5, showSymbol: rows.length <= 12,
        data: rows.map((r: any) => r.completed), lineStyle: { width: 2 },
        areaStyle: { color: '#456c5a', opacity: .04 },
      },
      {
        name: '缺勤', type: 'line', symbolSize: 4, showSymbol: rows.length <= 12,
        data: rows.map((r: any) => r.absent), lineStyle: { width: 1.5, type: 'dashed' },
      },
    ],
  }, true)
}

onMounted(() => {
  chart = echarts.init(trend.value!)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(trend.value!)
  render()
})
watch(() => props.summary, async () => { await nextTick(); render() }, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <div class="dashboard-grid">
    <div class="panel chart-wrap">
      <div class="panel-head">
        <div><h2>出勤趋势</h2><p>按日汇总</p></div>
        <span class="chart-caption">单位：人次</span>
      </div>
      <div ref="trend" class="chart" role="img" aria-label="所选日期内完成打卡与缺勤趋势" />
    </div>
    <div class="panel chart-wrap">
      <div class="panel-head">
        <h2>状态分布</h2>
        <span class="chart-caption">{{ props.summary ? total : '—' }} 条记录</span>
      </div>
      <div v-if="total > 0" class="distribution-list" aria-label="考勤状态及记录数">
        <div v-for="row in distribution" :key="row.name">
          <div class="distribution-label">
            <span>{{ statusNames[row.name] || row.name }}</span>
            <strong>{{ row.value }}</strong>
          </div>
          <div class="distribution-track" aria-hidden="true">
            <div class="distribution-fill" :data-status="row.name" :style="{ width: (row.value / total * 100) + '%' }" />
          </div>
        </div>
      </div>
      <div v-else class="empty-note">所选日期暂无记录</div>
    </div>
  </div>
</template>
