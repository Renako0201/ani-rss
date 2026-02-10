<template>
  <el-dialog v-model="dialogVisible" center title="RSS 任务队列">
    <div class="rss-task-panel">
      <div class="rss-task-head">
        <el-tag :type="status.running ? 'warning' : 'success'">{{ status.running ? "运行中" : "空闲" }}</el-tag>
        <span class="rss-task-meta">队列: {{ status.queueSize || 0 }}</span>
        <span class="rss-task-meta" v-if="status.startTime">开始: {{ toTime(status.startTime) }}</span>
      </div>

      <div class="rss-task-current">
        <span class="rss-task-label">当前任务:</span>
        <span>{{ status.currentTask || "-" }}</span>
      </div>

      <el-divider />

      <el-scrollbar class="rss-task-list-wrap">
        <el-empty v-if="!status.queue?.length" description="当前队列为空" />
        <div v-else class="rss-task-list">
          <div v-for="(name, idx) in status.queue" :key="`${name}-${idx}`" class="rss-task-item">
            <span class="rss-task-index">{{ idx + 1 }}.</span>
            <span class="rss-task-name">{{ name }}</span>
          </div>
        </div>
      </el-scrollbar>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref } from "vue";
import api from "@/js/api.js";

const dialogVisible = ref(false)
const status = ref({
  running: false,
  currentTask: "",
  queueSize: 0,
  queue: [],
  startTime: 0,
  lastFinishTime: 0
})

const show = () => {
  dialogVisible.value = true
  loopLoad()
}

const loadStatus = async () => {
  const res = await api.get("api/rssTaskStatus")
  status.value = res.data || status.value
}

const loopLoad = async () => {
  while (dialogVisible.value) {
    try {
      await loadStatus()
    } catch (_) {
    }
    await sleep(2000)
  }
}

const toTime = (ts) => {
  if (!ts) return "-"
  return new Date(ts).toLocaleString()
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

defineExpose({ show })
</script>

<style scoped>
.rss-task-panel {
  height: 460px;
  display: flex;
  flex-direction: column;
}

.rss-task-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rss-task-meta {
  opacity: 0.8;
  font-size: 12px;
}

.rss-task-current {
  margin-top: 10px;
}

.rss-task-label {
  opacity: 0.8;
  margin-right: 6px;
}

.rss-task-list-wrap {
  flex: 1;
}

.rss-task-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 6px;
}

.rss-task-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.rss-task-index {
  opacity: 0.7;
}

.rss-task-name {
  line-height: 1.4;
}
</style>

