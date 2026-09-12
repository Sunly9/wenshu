<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { kbApi } from '../api/http'
import { errMsg } from '../api/http'

const route = useRoute()
const router = useRouter()
const failed = ref('')

onMounted(async () => {
  try {
    const kb = await kbApi.join(String(route.params.code).toUpperCase())
    ElMessage.success(`已加入「${kb.name}」`)
    router.replace(kb.id ? `/kb/${kb.id}` : '/')
  } catch (e) {
    failed.value = errMsg(e)
  }
})
</script>

<template>
  <el-empty :description="failed || '正在通过口令加入资料库…'">
    <el-button v-if="failed" type="primary" @click="router.replace('/')">返回首页</el-button>
  </el-empty>
</template>
