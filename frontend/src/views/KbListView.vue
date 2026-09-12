<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Key, Plus } from '@element-plus/icons-vue'
import { errMsg, kbApi } from '../api/http'
import type { KbInfo } from '../api/types'

const router = useRouter()
const kbs = ref<KbInfo[]>([])
const loading = ref(false)

const createDialog = ref(false)
const createForm = reactive({ name: '', description: '' })

const joinDialog = ref(false)
const joinCode = ref('')

async function load() {
  loading.value = true
  try {
    kbs.value = await kbApi.list()
  } catch (e) {
    ElMessage.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请输入库名')
    return
  }
  try {
    const kb = await kbApi.create(createForm.name.trim(), createForm.description.trim())
    ElMessage.success(`已创建「${kb.name}」，口令 ${kb.shareCode}`)
    createDialog.value = false
    createForm.name = ''
    createForm.description = ''
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

async function join() {
  if (joinCode.value.trim().length !== 6) {
    ElMessage.warning('口令为 6 位')
    return
  }
  try {
    const kb = await kbApi.join(joinCode.value.trim().toUpperCase())
    ElMessage.success(`已加入「${kb.name}」`)
    joinDialog.value = false
    joinCode.value = ''
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <h2>我的资料库</h2>
      <div>
        <el-button :icon="Key" @click="joinDialog = true">口令加入</el-button>
        <el-button type="primary" :icon="Plus" @click="createDialog = true">新建资料库</el-button>
      </div>
    </div>

    <el-empty v-if="!loading && kbs.length === 0" description="还没有资料库——建一个，把备考资料丢进来" />

    <el-row :gutter="16">
      <el-col v-for="kb in kbs" :key="kb.id" :xs="24" :sm="12" :md="8">
        <el-card class="kb-card" shadow="hover" @click="router.push(`/kb/${kb.id}`)">
          <div class="kb-title">{{ kb.name }}</div>
          <div class="kb-desc">{{ kb.description || '—' }}</div>
          <div class="kb-meta">
            <span>{{ kb.documentCount }} 份资料</span>
            <span v-if="kb.owner" class="kb-owner">我创建的 · 口令 {{ kb.shareCode }}</span>
            <span v-else>已通过口令加入</span>
          </div>
          <div class="kb-actions">
            <el-button
              type="primary"
              text
              :icon="ChatDotRound"
              @click.stop="router.push(`/chat/${kb.id}`)"
            >
              开始提问
            </el-button>
            <el-button text size="small" @click.stop="router.push(`/debug/${kb.id}`)">调试台</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="createDialog" title="新建资料库" width="440px">
      <el-form label-width="60px">
        <el-form-item label="库名" required>
          <el-input v-model="createForm.name" maxlength="64" placeholder="如：数据结构期末" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" maxlength="255" placeholder="放什么资料（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="joinDialog" title="通过口令加入资料库" width="440px">
      <el-input v-model="joinCode" maxlength="6" placeholder="输入 6 位口令，如 K7Q4MD" style="letter-spacing: 4px" />
      <div class="join-tip">同学把口令或加入链接发给你，输一次即可永久绑定这个浏览器。</div>
      <template #footer>
        <el-button @click="joinDialog = false">取消</el-button>
        <el-button type="primary" @click="join">加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0;
}
.kb-card {
  margin-bottom: 16px;
  cursor: pointer;
}
.kb-title {
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 6px;
}
.kb-desc {
  color: var(--ws-text-light);
  font-size: 13px;
  min-height: 18px;
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--ws-text-light);
}
.kb-owner {
  color: var(--ws-primary);
}
.kb-actions {
  margin-top: 8px;
  display: flex;
  align-items: center;
}
.join-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--ws-text-light);
}
</style>
