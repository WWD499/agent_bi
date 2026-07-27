<template>
  <div ref="el" class="chart-box"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true }
})

const el = ref(null)
let chart = null

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(props.option, true)
}

function resize() {
  chart && chart.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

watch(
  () => props.option,
  () => render(),
  { deep: true }
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
</script>

<style scoped>
.chart-box {
  width: 100%;
  height: 100%;
  /* 兜底最小高度：父容器未显式给高时（如对话气泡），仍保证图表可见 */
  min-height: 320px;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 6px;
  box-sizing: border-box;
}
</style>
