<template>
  <el-config-provider :locale="zhCn" :link="linkConfig" :dialog="dialogConfig">
    <Login v-if="!authorization"/>
    <App v-else/>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import Login from "@/Login.vue";
import App from "@/home/App.vue";
import {authorization, init} from "@/js/global.js";
import {reactive} from "vue";

/**
 * 链接配置
 */
let linkConfig = reactive({
  type: 'primary',
  underline: 'never'
})

let dialogConfig = reactive({
  alignCenter: true,
  // 页面主体使用内部 el-scrollbar，不依赖 body 滚动。
  // 关闭 dialog 的 body 锁滚动，避免打开弹窗时 body 宽度补偿导致布局抖动。
  lockScroll: false
})

init()

</script>
