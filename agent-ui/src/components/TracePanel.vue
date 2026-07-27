<template>
  <div class="trace" :class="'t-' + item.type">
    <div class="trace-head" :class="{ clickable: hasDetail }" @click="toggle">
      <span class="ic">{{ icon }}</span>
      <span class="lbl">{{ label }}</span>
      <span v-if="hasDetail" class="chev">{{ open ? '▴' : '▾' }}</span>
    </div>
    <div v-if="hasDetail && open" class="trace-body">
      <ChartBlock v-if="item.type === 'tool_result' && item.chartOption" :option="item.chartOption" />
      <pre v-else class="code-box">{{ detail }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import ChartBlock from './ChartBlock.vue'

const props = defineProps({
  item: { type: Object, required: true }
})

const open = ref(true)

const hasDetail = computed(() => {
  const t = props.item.type
  return (
    (t === 'tool_call' && props.item.args != null) ||
    t === 'tool_result' ||
    t === 'reasoning' ||
    t === 'error'
  )
})

const icon = computed(
  () =>
    ({ tool_call: '🔧', tool_result: '📊', reasoning: '🧠', error: '⚠️' }[props.item.type] || '•')
)

const label = computed(() => {
  const t = props.item.type
  if (t === 'tool_call') return '调用工具 · ' + props.item.tool
  if (t === 'tool_result') return '工具返回 · ' + props.item.tool
  if (t === 'reasoning') return '推理过程'
  if (t === 'error') return '出错'
  return t
})

const detail = computed(() => {
  const t = props.item.type
  if (t === 'tool_call') return pretty(props.item.args)
  if (t === 'tool_result') return pretty(props.item.result)
  if (t === 'reasoning' || t === 'error') return props.item.text || ''
  return ''
})

function pretty(v) {
  if (v == null) return ''
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function toggle() {
  if (hasDetail.value) open.value = !open.value
}
</script>

<style scoped>
.trace {
  margin: 9px 0;
  border-left: 3px solid var(--border);
  padding: 3px 0 3px 12px;
}
.t-tool_call {
  border-color: var(--tool);
}
.t-tool_result {
  border-color: var(--result);
}
.t-reasoning {
  border-color: var(--reason);
}
.t-error {
  border-color: var(--error);
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}
.clickable {
  cursor: pointer;
}
.ic {
  font-size: 14px;
}
.lbl {
  color: var(--text);
}
.chev {
  color: var(--text-dim);
  font-size: 11px;
}
.trace-body {
  margin-top: 6px;
}
</style>
