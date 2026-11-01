<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { statusNames } from '../composables'

interface Summary {
  trend: { date: string; completed: number; absent: number }[]
  distribution: { name: string; value: number }[]
}
const props = defineProps<{ summary: Summary | null }>()
const trend = ref<HTMLDivElement | null>(null)
type TrendSeries = '完成打卡' | '缺勤'
const visibleSeries = ref<Record<TrendSeries, boolean>>({ '完成打卡': true, '缺勤': true })
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
const statusColors: Record<string, string> = {
  NORMAL: '#496e51', PENDING: '#bdc6b3', ABSENT: '#b37c67',
  MISSING_IN: '#bea375', MISSING_OUT: '#d3bd99', LEAVE: '#8ca4a2', REST: '#e4e7db',
}
const distribution = computed(() => props.summary?.distribution || [])
const total = computed(() => distribution.value.reduce((sum, row) => sum + row.value, 0))
const recordedDays = computed(() => props.summary?.trend.length || 0)
function stateName(name: string) { return name === 'NORMAL' ? '完成打卡' : statusNames[name] || name }
function toggleSeries(name: TrendSeries) {
  visibleSeries.value[name] = !visibleSeries.value[name]
  chart?.dispatchAction({ type: visibleSeries.value[name] ? 'legendSelect' : 'legendUnSelect', name })
}

function render() {
  if (!chart) return
  const rows = props.summary?.trend || []
  chart.setOption({
    animation: false,
    color: ['#476b50', '#b6846c'],
    legend: { show: false, data: ['完成打卡', '缺勤'], selected: { ...visibleSeries.value } },
    textStyle: { fontFamily: '"Segoe UI", "Noto Sans SC", "Microsoft YaHei", sans-serif' },
    graphic: rows.length ? [] : [{
      type: 'text', left: 'center', top: '42%',
      style: { text: '所选日期暂无记录', fill: '#838a7d', fontSize: 12 },
    }],
    tooltip: {
      trigger: 'axis', confine: true, backgroundColor: '#fffefa', borderColor: '#e1e5d9',
      padding: [12, 16], extraCssText: 'border-radius:8px;box-shadow:0 6px 24px rgba(41,58,39,.08);',
      textStyle: { color: '#353f37', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#a8b49f', type: 'dashed' } },
    },
    grid: { top: 20, right: 12, bottom: 14, left: 8, containLabel: true },
    xAxis: {
      type: 'category', boundaryGap: false, data: rows.map(row => row.date),
      axisLine: { lineStyle: { color: '#e5e9dd' } }, axisTick: { show: false },
      axisLabel: { color: '#8b9385', fontSize: 10, margin: 15, hideOverlap: true, formatter: (value: string) => value.slice(5).replace('-', '/') },
    },
    yAxis: {
      type: 'value', minInterval: 1, splitNumber: 4,
      axisLabel: { color: '#8b9385', fontSize: 10, margin: 13 },
      splitLine: { lineStyle: { color: '#edf0e6', type: [3, 5] } },
    },
    series: [
      {
        name: '完成打卡', type: 'line', symbol: 'circle', symbolSize: 6,
        showSymbol: rows.length <= 10, data: rows.map(row => row.completed),
        lineStyle: { width: 2.5 }, itemStyle: { borderColor: '#fffefa', borderWidth: 2 },
        emphasis: { scale: 1.5 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#d3dfc399' }, { offset: 1, color: '#f9faf300' },
        ]) },
      },
      {
        name: '缺勤', type: 'line', symbol: 'circle', symbolSize: 4,
        showSymbol: rows.length <= 10, data: rows.map(row => row.absent),
        lineStyle: { width: 1.8, type: [4, 4] }, itemStyle: { borderColor: '#fffefa', borderWidth: 1 },
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
  <section class="attendance-analysis" aria-label="考勤分析">
    <div class="trend-section">
      <header class="analysis-heading">
        <div><h2>出勤走势</h2><p>每日完成打卡与缺勤记录</p></div>
        <div class="trend-legend" role="group" aria-label="显示的趋势系列">
          <button type="button" :aria-pressed="visibleSeries['完成打卡']" :class="{ 'is-muted': !visibleSeries['完成打卡'] }" :title="visibleSeries['完成打卡'] ? '隐藏完成打卡' : '显示完成打卡'" @click="toggleSeries('完成打卡')"><i class="legend-completed" />完成打卡</button>
          <button type="button" :aria-pressed="visibleSeries['缺勤']" :class="{ 'is-muted': !visibleSeries['缺勤'] }" :title="visibleSeries['缺勤'] ? '隐藏缺勤' : '显示缺勤'" @click="toggleSeries('缺勤')"><i class="legend-absent" />缺勤</button>
        </div>
      </header>
      <div class="chart-unit">每日记录数</div>
      <div ref="trend" class="trend-chart" role="img" aria-label="所选日期内每日完成打卡与缺勤记录数折线图" />
      <footer class="trend-footer"><span>完成打卡包含迟到、早退</span><span>{{ props.summary ? recordedDays : '—' }} 个统计日</span></footer>
    </div>
    <aside class="composition-section" aria-label="考勤状态分布">
      <header class="composition-heading"><h2>考勤构成</h2><span>按每日记录</span></header>
      <div class="composition-total"><strong>{{ props.summary ? total.toLocaleString('zh-CN') : '—' }}</strong><span>条记录</span></div>
      <template v-if="total > 0">
        <div class="composition-bar" aria-hidden="true">
          <span v-for="row in distribution.filter(item => item.value > 0)" :key="row.name" :style="{ flexGrow: row.value, backgroundColor: statusColors[row.name] || '#a0ab95' }" :title="stateName(row.name) + '：' + row.value + ' 条'" />
        </div>
        <div class="composition-list">
          <div v-for="row in distribution" :key="row.name" class="composition-row">
            <span><i :style="{ backgroundColor: statusColors[row.name] || '#a0ab95' }" />{{ stateName(row.name) }}</span><strong>{{ row.value.toLocaleString('zh-CN') }}</strong>
          </div>
        </div>
      </template>
      <div v-else class="composition-empty">暂无可分析的记录</div>
      <p class="composition-note">每人每天对应一条记录，含休息日与待完成记录。</p>
    </aside>
  </section>
</template>

<style scoped>
.attendance-analysis { display: grid; grid-template-columns: minmax(0, 1fr) 282px; margin-bottom: 24px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); overflow: hidden; }
.trend-section { display: flex; flex-direction: column; min-width: 0; padding: 25px 26px 18px; }
.analysis-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.analysis-heading h2, .composition-heading h2 { margin: 0; font-size: 16px; font-weight: 600; }
.analysis-heading p { margin: 5px 0 0; font-size: 11px; color: var(--text-muted); }
.trend-legend { display: flex; flex-wrap: wrap; gap: 15px; padding-top: 5px; }
.trend-legend > button { display: inline-flex; align-items: center; gap: 6px; min-height: 28px; margin: -5px -6px; padding: 5px 6px; border: 0; border-radius: 4px; background: transparent; color: #737f6a; font-size: 10px; white-space: nowrap; cursor: pointer; }
.trend-legend > button:hover { background: #f0f3e9; }
.trend-legend > button.is-muted { text-decoration: line-through; }
.trend-legend > button.is-muted i { opacity: .35; }
.trend-legend i { display: inline-block; width: 13px; border-top: 2px solid #476b50; }
.trend-legend .legend-absent { border-color: #b6846c; border-top-style: dashed; }
.chart-unit { margin-top: 22px; color: #8a9383; font-size: 10px; }
.trend-chart { flex: 1; height: 242px; min-height: 242px; width: 100%; min-width: 0; }
.trend-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding-top: 12px; border-top: 1px solid #eef0e8; color: #87917f; font-size: 10px; }
.composition-section { display: flex; flex-direction: column; min-width: 0; padding: 26px 24px 19px; border-left: 1px solid #e7eade; background: #fafbf6; }
.composition-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.composition-heading h2 { font-size: 13px; }
.composition-heading > span { color: #8c9582; font-size: 10px; }
.composition-total { display: flex; align-items: baseline; gap: 9px; margin-top: 17px; }
.composition-total strong { font-size: 35px; font-weight: 500; color: #3a4a33; line-height: 1.1; letter-spacing: -1px; font-variant-numeric: tabular-nums; }
.composition-total > span { color: #8a9380; font-size: 10px; }
.composition-bar { display: flex; gap: 3px; height: 10px; border-radius: 3px; overflow: hidden; margin: 17px 0 18px; background: #eff2e8; }
.composition-bar > span { flex-basis: 0; min-width: 1px; }
.composition-list { display: grid; gap: 11px; }
.composition-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; color: #77836d; font-size: 11px; }
.composition-row > span { display: flex; align-items: center; gap: 8px; }
.composition-row i { display: block; height: 6px; width: 6px; border-radius: 2px; }
.composition-row strong { color: #4a5941; font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.composition-note { margin: auto 0 0; padding-top: 18px; color: #929a88; font-size: 10px; line-height: 1.8; }
.composition-empty { display: flex; align-items: center; flex: 1; padding: 28px 0; color: var(--text-muted); font-size: 12px; }
@media (max-width: 1180px) {
  .attendance-analysis { grid-template-columns: minmax(0, 1fr) 242px; }
  .trend-section { padding: 24px 21px 18px; }
  .composition-section { padding-left: 21px; padding-right: 21px; }
  .analysis-heading { flex-direction: column; gap: 10px; }
  .chart-unit { margin-top: 14px; }
}
@media (max-width: 950px) {
  .attendance-analysis { grid-template-columns: minmax(0, 1fr); }
  .analysis-heading { flex-direction: row; }
  .composition-section { display: grid; grid-template-columns: minmax(0, .7fr) minmax(0, 1.3fr); gap: 12px 28px; border-left: 0; border-top: 1px solid #e7eade; padding-top: 21px; }
  .composition-heading { grid-column: 1; }
  .composition-heading > span { display: none; }
  .composition-total { grid-column: 1; margin: 0; }
  .composition-bar { grid-column: 1; margin: 0; align-self: start; }
  .composition-list { grid-column: 2; grid-row: 1 / 5; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 24px; align-content: start; }
  .composition-note { grid-column: 1; padding-top: 0; }
  .composition-empty { grid-column: 2; grid-row: 1 / 4; padding: 0; }
}
@media (max-width: 600px) {
  .trend-section { padding: 21px 18px 16px; }
  .analysis-heading { flex-direction: column; gap: 11px; }
  .trend-legend { padding: 0; }
  .trend-chart { height: 220px; min-height: 220px; }
  .composition-section { display: flex; gap: 0; padding: 21px 22px; }
  .composition-heading > span { display: block; }
  .composition-total { margin-top: 13px; }
  .composition-bar { margin: 16px 0 19px; align-self: stretch; }
  .composition-list { gap: 14px 25px; }
  .composition-note { margin-top: 17px; }
  .composition-empty { padding: 22px 0 0; }
}
</style>
