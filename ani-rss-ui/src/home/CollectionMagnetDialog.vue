<template>
  <el-dialog
      v-model="dialogVisible"
      title="合集下载管理"
      width="900px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      :before-close="handleBeforeClose"
      class="collection-magnet-dialog"
  >
    <div v-loading="loading" class="dialog-content">
      <!-- 任务状态 -->
      <div class="task-status">
        <div class="status-header">
          <div class="status-info">
            <span class="status-label">任务状态:</span>
            <el-tag :type="getStatusType(task.status)" size="large">
              {{ getStatusText(task.status) }}
            </el-tag>
          </div>
          <div class="status-actions">
            <el-button
                v-if="task.status === 'downloading'"
                type="danger"
                size="small"
                @click="$emit('cancel')"
            >
              取消任务
            </el-button>
          </div>
        </div>
        
        <el-progress 
            v-if="task.status === 'downloading'" 
            :percentage="task.progress || 0"
            :status="task.progress === 100 ? 'success' : ''"
        />
      </div>

      <!-- 文件树 -->
      <div v-if="task.files && task.files.length > 0" class="file-tree-section">
        <div class="tree-header">
          <div class="tree-title">
            <span>文件列表</span>
            <el-checkbox 
                v-model="selectAll"
                @change="handleSelectAll"
                class="select-all-checkbox"
            >
              全选
            </el-checkbox>
          </div>
          <div class="tree-actions">
            <el-button type="primary" size="small" @click="expandAll">
              {{ allExpanded ? '折叠全部' : '展开全部' }}
            </el-button>
            <el-button type="success" size="small" @click="autoRenameAll">
              自动重命名
            </el-button>
          </div>
        </div>

        <div class="file-tree">
          <div 
              v-for="node in flattenedFiles" 
              :key="node.path"
              class="tree-node"
              :data-level="node.level"
              :style="{ marginLeft: (node.level * 20) + 'px' }"
              :class="{ 
                'is-directory': node.isDir, 
                'is-video': isVideoFile(node.name),
                'is-selected': node.selected 
              }"
          >
            <div class="node-content">
              <!-- 展开/折叠图标 -->
              <span 
                  v-if="node.isDir && node.children && node.children.length > 0"
                  class="expand-icon"
                  @click="toggleExpand(node)"
              >
                <el-icon>
                  <arrow-right v-if="!node.expanded" />
                  <arrow-down v-else />
                </el-icon>
              </span>
              <span v-else class="expand-placeholder"></span>

              <!-- 复选框 -->
              <el-checkbox 
                  v-model="node.selected"
                  @change="(val) => handleSelectChange(node, val)"
                  class="node-checkbox"
              />

              <!-- 文件图标 -->
              <el-icon class="file-icon">
                <folder v-if="node.isDir" />
                <video-play v-else-if="isVideoFile(node.name)" />
                <document v-else />
              </el-icon>

              <!-- 文件名 -->
              <div class="file-info">
                <div class="file-name-row">
                  <span class="file-name" :title="node.name">{{ node.name }}</span>
                  <el-tag v-if="node.episode" size="small" type="success" class="episode-tag">
                    第{{ node.episode }}集
                  </el-tag>
                </div>
                <span v-if="!node.isDir && node.size" class="file-size">
                  {{ formatFileSize(node.size) }}
                </span>
              </div>
            </div>

            <!-- 重命名输入框 -->
            <div v-if="!node.isDir && node.selected" class="rename-row">
              <el-input
                  v-model="node.newName"
                  size="small"
                  placeholder="新文件名（留空使用原名）"
                  class="rename-input"
              >
                <template #suffix v-if="getFileExt(node.name)">
                  .{{ getFileExt(node.name) }}
                </template>
              </el-input>
              <el-button 
                  v-if="node.episode"
                  type="primary" 
                  size="small"
                  link
                  @click="autoRenameSingle(node)"
              >
                自动
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 预览区域 -->
      <div v-if="hasSelectedFiles" class="preview-section">
        <div class="preview-title">整理预览</div>
        <div class="preview-content">
          <div class="preview-item">
            <span class="preview-label">已选择:</span>
            <span class="preview-value">{{ selectedCount }} 个文件</span>
          </div>
          <div class="preview-item">
            <span class="preview-label">目标路径:</span>
            <span class="preview-value path">{{ task.finalPath }}</span>
          </div>
          <div class="preview-item">
            <span class="preview-label">保留目录结构:</span>
            <el-switch v-model="keepDirectoryStructure" />
          </div>
          <div v-if="keepDirectoryStructure" class="preview-item">
            <span class="preview-label">说明:</span>
            <span class="preview-value">将保留原有的目录结构，你可以编辑目录名称</span>
          </div>
        </div>
      </div>
      
      <!-- 目录编辑区域 （仅在保留目录结构时显示）-->
      <div v-if="keepDirectoryStructure && hasSelectedFiles" class="directory-edit-section">
        <div class="section-title">目录名编辑</div>
        <div class="directory-list">
          <div 
              v-for="dir in directoriesToRename" 
              :key="dir.originalPath"
              class="directory-item"
              :style="{ paddingLeft: (10 + dir.level * 28) + 'px' }"
          >
            <span class="dir-label">{{ dir.displayName }}</span>
            <el-input 
                v-model="dir.newName"
                size="small"
                placeholder="目录新名称"
                class="dir-input"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <template #footer>
      <div class="dialog-footer">
        <el-button
            type="danger"
            :disabled="organizing"
            @click="$emit('exit-and-cleanup')"
        >
          退出并清理临时文件
        </el-button>
        <el-button
            v-if="task.status === 'completed'"
            type="primary"
            :disabled="!hasSelectedFiles"
            :loading="organizing"
            @click="handleOrganize"
        >
          确认整理
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ArrowRight, ArrowDown, Folder, VideoPlay, Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: Boolean,
  task: {
    type: Object,
    default: () => ({
      status: '',
      progress: 0,
      files: [],
      finalPath: ''
    })
  },
  loading: Boolean,
  organizing: Boolean
})

const emit = defineEmits(['update:modelValue', 'organize', 'cancel', 'exit-and-cleanup'])

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const handleBeforeClose = () => {
  ElMessage.warning('请先完成文件整理，当前窗口不可关闭')
}

const selectAll = ref(false)
const allExpanded = ref(true)
const keepDirectoryStructure = ref(false)
const directoriesToRename = ref([])

// 计算扁平化的文件列表（根据展开状态）
const flattenedFiles = computed(() => {
  const result = []
  const traverse = (nodes) => {
    for (const node of nodes || []) {
      result.push(node)
      if (node.isDir && node.expanded && node.children) {
        traverse(node.children)
      }
    }
  }
  traverse(props.task.files)
  return result
})

// 是否有选中的文件
const hasSelectedFiles = computed(() => {
  const hasSelected = (nodes) => {
    for (const node of nodes || []) {
      if (node.selected && !node.isDir) return true
      if (node.children && hasSelected(node.children)) return true
    }
    return false
  }
  return hasSelected(props.task.files)
})

// 选中的文件数量
const selectedCount = computed(() => {
  let count = 0
  const countSelected = (nodes) => {
    for (const node of nodes || []) {
      if (node.selected && !node.isDir) count++
      if (node.children) countSelected(node.children)
    }
  }
  countSelected(props.task.files)
  return count
})

// 状态相关
const getStatusType = (status) => {
  const map = {
    'downloading': 'primary',
    'completed': 'success',
    'failed': 'danger',
    'organizing': 'warning',
    'finished': 'success'
  }
  return map[status] || 'info'
}

const getStatusText = (status) => {
  const map = {
    'downloading': '下载中',
    'completed': '下载完成',
    'failed': '下载失败',
    'organizing': '整理中',
    'finished': '已完成'
  }
  return map[status] || '等待中'
}

// 文件操作
const isVideoFile = (filename) => {
  const ext = filename?.split('.').pop().toLowerCase()
  return ['mkv', 'mp4', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm2ts', 'ts'].includes(ext)
}

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const getFileExt = (filename) => {
  return filename?.split('.').pop() || ''
}

// 选择操作
const handleSelectAll = (val) => {
  const setSelected = (nodes) => {
    for (const node of nodes || []) {
      node.selected = val
      if (node.children) setSelected(node.children)
    }
  }
  setSelected(props.task.files)
}

const handleSelectChange = (node, val) => {
  // 如果是目录，同步设置子文件
  if (node.isDir && node.children) {
    const setChildren = (children) => {
      for (const child of children) {
        child.selected = val
        if (child.children) setChildren(child.children)
      }
    }
    setChildren(node.children)
  }
}

// 展开/折叠
const toggleExpand = (node) => {
  node.expanded = !node.expanded
}

const expandAll = () => {
  allExpanded.value = !allExpanded.value
  const setExpanded = (nodes) => {
    for (const node of nodes || []) {
      if (node.isDir) {
        node.expanded = allExpanded.value
        if (node.children) setExpanded(node.children)
      }
    }
  }
  setExpanded(props.task.files)
}

// 自动重命名
const autoRenameSingle = (node) => {
  if (!node.episode) {
    ElMessage.warning('无法识别集数')
    return
  }
  
  // 使用 RenameUtil 的逻辑生成新文件名
  const ext = getFileExt(node.name)
  const season = props.task.ani?.season || 1
  const episode = node.episode
  
  // 格式: S01E01
  const newName = `S${String(season).padStart(2, '0')}E${String(Math.floor(episode)).padStart(2, '0')}`
  node.newName = newName + '.' + ext
  
  ElMessage.success(`已自动重命名为: ${node.newName}`)
}

const autoRenameAll = () => {
  let count = 0
  const renameNodes = (nodes) => {
    for (const node of nodes || []) {
      if (!node.isDir && node.selected && node.episode) {
        const ext = getFileExt(node.name)
        const season = props.task.ani?.season || 1
        const episode = node.episode
        const newName = `S${String(season).padStart(2, '0')}E${String(Math.floor(episode)).padStart(2, '0')}`
        node.newName = newName + '.' + ext
        count++
      }
      if (node.children) renameNodes(node.children)
    }
  }
  renameNodes(props.task.files)
  
  if (count > 0) {
    ElMessage.success(`已为 ${count} 个文件自动重命名`)
  } else {
    ElMessage.warning('没有可重命名的文件')
  }
}

// 整理
const handleOrganize = () => {
  // 收集所有选中的文件（扁平化）
  const selectedFiles = []
  const collectFiles = (nodes) => {
    for (const node of nodes || []) {
      if (!node.isDir) {
        selectedFiles.push(node)
      }
      if (node.children) collectFiles(node.children)
    }
  }
  collectFiles(props.task.files)
  
  // 传递参数给后端
  emit('organize', {
    files: selectedFiles,
    keepDirectoryStructure: keepDirectoryStructure.value,
    directoryRenames: buildDirectoryRenames()
  })
}

/**
 * 构建目录重命名映射
 */
const buildDirectoryRenames = () => {
  const renames = {}
  for (const dir of directoriesToRename.value) {
    if (dir.newName && dir.newName !== dir.displayName) {
      renames[dir.originalPath] = dir.newName
    }
  }
  return renames
}

/**
 * 提取目录列表（带层级结构）
 */
const extractDirectoriesToRename = () => {
  const dirs = []
  
  // 检测并跳过最外层的单一根目录
  let rootDirToSkip = null
  let baseLevel = 0
  
  if (props.task.files && props.task.files.length === 1 && props.task.files[0].isDir) {
    const singleRootDir = props.task.files[0]
    // 检查所有选中的文件是否都在这个目录下
    let allInRoot = true
    const checkAllInRoot = (nodes) => {
      for (const node of nodes || []) {
        if (node.selected && !node.isDir) {
          if (!node.path.startsWith(singleRootDir.path + '/')) {
            allInRoot = false
            return
          }
        }
        if (node.children) {
          checkAllInRoot(node.children)
        }
      }
    }
    checkAllInRoot(props.task.files)
    
    if (allInRoot) {
      rootDirToSkip = singleRootDir.path
      baseLevel = -1  // 跳过的目录层级设为 -1，其子目录从 0 开始
      console.log('[extractDirectoriesToRename] 跳过最外层根目录:', singleRootDir.name)
    }
  }
  
  const traverse = (node, level) => {
    // 跳过最外层的根目录
    if (rootDirToSkip && node.path === rootDirToSkip) {
      if (node.children) {
        for (const child of node.children) {
          traverse(child, level + 1)
        }
      }
      return
    }
    
    if (node.isDir && node.selected) {
      // 计算相对于被跳过的根目录的层级
      const actualLevel = rootDirToSkip ? Math.max(0, level) : level
      
      dirs.push({
        originalPath: node.path,
        displayName: node.name,
        newName: node.name,
        level: actualLevel,
        children: node.children || [],
        isSkipped: false
      })
      
      if (node.children) {
        for (const child of node.children) {
          traverse(child, level + 1)
        }
      }
    }
  }
  
  if (props.task.files) {
    for (const file of props.task.files) {
      traverse(file, baseLevel)
    }
  }
  
  directoriesToRename.value = dirs
  console.log('[extractDirectoriesToRename] 提取的目录:', dirs)
}

// 监听文件列表变化，更新目录列表
watch([() => props.task.files, keepDirectoryStructure], ([files, keepDir]) => {
  if (keepDir && files) {
    extractDirectoriesToRename()
  }
}, { deep: true })

// 监听任务完成，自动展开
watch(() => props.task.status, (newStatus) => {
  if (newStatus === 'completed') {
    allExpanded.value = true
  }
})
</script>

<style scoped>
.collection-magnet-dialog :deep(.el-dialog) {
  max-height: 90vh;
  display: flex;
  flex-direction: column;
}

.collection-magnet-dialog :deep(.el-dialog__body) {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
  max-height: calc(90vh - 180px);
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-bottom: 20px;
}

.task-status {
  background: #f5f7fa;
  padding: 15px;
  border-radius: 8px;
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.status-actions {
  display: flex;
  gap: 8px;
}

.status-label {
  font-weight: bold;
  margin-right: 10px;
}

.file-tree-section {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 15px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}

.tree-title {
  display: flex;
  align-items: center;
  gap: 15px;
  font-weight: bold;
}

.select-all-checkbox {
  font-weight: normal;
}

.tree-actions {
  display: flex;
  gap: 10px;
}

.file-tree {
  max-height: 400px;
  overflow-y: auto;
  padding: 10px 0;
}

.tree-node {
  padding: 6px 0;
  padding-right: 10px;
  border-bottom: 1px solid #f0f0f0;
  transition: background-color 0.2s;
}

.tree-node:hover {
  background-color: #f5f7fa;
}

.tree-node:last-child {
  border-bottom: none;
}

.tree-node.is-directory {
  background-color: #fafafa;
  font-weight: 500;
}

.tree-node.is-selected {
  background-color: #f0f9ff;
}

.node-content {
  display: flex;
  align-items: center;
  gap: 8px;
}

.expand-icon {
  cursor: pointer;
  width: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.expand-placeholder {
  width: 20px;
}

.node-checkbox {
  margin-right: 4px;
}

.file-icon {
  font-size: 18px;
  color: #909399;
}

.tree-node.is-video .file-icon {
  color: #409eff;
}

.tree-node.is-directory .file-icon {
  color: #e6a23c;
}

.file-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.file-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.episode-tag {
  flex-shrink: 0;
}

.file-size {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.rename-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  margin-left: 60px;
}

.rename-input {
  flex: 1;
  max-width: 400px;
}

.preview-section {
  background: #f0f9ff;
  padding: 15px;
  border-radius: 8px;
  border-left: 4px solid #409eff;
}

.preview-title {
  font-weight: bold;
  margin-bottom: 10px;
  color: #409eff;
}

.preview-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preview-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.preview-label {
  color: #606266;
  flex-shrink: 0;
}

.preview-value {
  color: #303133;
  font-weight: 500;
}

.preview-value.path {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}

.directory-edit-section {
  background: #fdf6ec;
  padding: 15px;
  border-radius: 8px;
  border-left: 4px solid #e6a23c;
  margin-top: 15px;
  margin-bottom: 20px;
}

.section-title {
  font-weight: bold;
  margin-bottom: 12px;
  color: #e6a23c;
  font-size: 14px;
}

.directory-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 300px;
  overflow-y: auto;
}

.directory-item {
  display: flex;
  flex-direction: column;
  padding: 10px;
  background: white;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
  transition: all 0.2s;
}

.directory-item:hover {
  border-color: #e6a23c;
  box-shadow: 0 2px 8px rgba(230, 162, 60, 0.1);
}

.dir-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dir-icon {
  color: #e6a23c;
  font-size: 18px;
  flex-shrink: 0;
}

.dir-path {
  flex: 1;
  color: #303133;
  font-size: 14px;
  font-weight: 500;
  word-break: break-all;
}
.dir-label {
  color: #303133;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
}
.skip-tag {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}

.dir-children-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  margin-left: 28px;
}

.dir-rename-input {
  width: 200px;
  flex-shrink: 0;
}

.dir-input {
  flex: 1;
  min-width: 200px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>


