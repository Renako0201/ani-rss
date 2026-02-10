<template>
  <template v-if="notificationConfig['notificationType'] === 'RCLONE_SYNC'">
    <el-form-item label="SSH Host">
      <el-input v-model="notificationConfig['rcloneSyncSshHost']" placeholder="192.168.1.2"/>
    </el-form-item>
    <el-form-item label="SSH Port">
      <el-input-number v-model="notificationConfig['rcloneSyncSshPort']" :min="1" :max="65535"
                       class="notification-input-width"/>
    </el-form-item>
    <el-form-item label="SSH User">
      <el-input v-model="notificationConfig['rcloneSyncSshUser']" placeholder="root"/>
    </el-form-item>
    <el-form-item label="SSH Bin">
      <el-input v-model="notificationConfig['rcloneSyncSshBin']" placeholder="ssh"/>
    </el-form-item>
    <el-form-item label="Rclone Bin">
      <el-input v-model="notificationConfig['rcloneSyncRcloneBin']" placeholder="rclone"/>
    </el-form-item>
    <el-form-item label="RC URL">
      <el-input v-model="notificationConfig['rcloneSyncRcUrl']" placeholder="http://127.0.0.1:5572"/>
    </el-form-item>
    <el-form-item label="RC User">
      <el-input v-model="notificationConfig['rcloneSyncRcUser']" placeholder=""/>
    </el-form-item>
    <el-form-item label="RC Pass">
      <el-input v-model="notificationConfig['rcloneSyncRcPass']" placeholder="" show-password/>
    </el-form-item>
    <el-form-item label="Source FS">
      <el-input v-model="notificationConfig['rcloneSyncSourceFs']" type="textarea"
                :autosize="{ minRows: 2}" placeholder="pikpak:/Anime/${title}/Season ${season}"/>
    </el-form-item>
    <el-form-item label="Target FS">
      <el-input v-model="notificationConfig['rcloneSyncTargetFs']" type="textarea"
                :autosize="{ minRows: 2}" placeholder="onedrive:/Anime/${title}/Season ${season}"/>
    </el-form-item>
    <el-form-item label="Episode Include">
      <el-input v-model="notificationConfig['rcloneSyncEpisodeInclude']"
                placeholder="*S${seasonFormat}E${episodeFormat}*"/>
    </el-form-item>
    <el-form-item label="Collection">
      <el-checkbox v-model="notificationConfig['rcloneSyncCollectionUseSync']" label="合集模式使用 sync/sync"/>
    </el-form-item>
    <el-form-item label="Extra Args">
      <el-input v-model="notificationConfig['rcloneSyncExtraArgs']" type="textarea"
                :autosize="{ minRows: 2}"
                placeholder="_transfers=4&#10;_checkers=8"/>
    </el-form-item>
  </template>
</template>

<script setup>
let props = defineProps(['notificationConfig', 'config'])
const notificationConfig = props.notificationConfig
</script>
