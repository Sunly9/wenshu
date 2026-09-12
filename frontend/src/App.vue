<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">
        <span class="brand-name">问书</span>
        <span class="brand-sub">每个答案都有出处</span>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--ws-border);
  background: #fff;
  height: 56px;
}
.brand {
  cursor: pointer;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.brand-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--ws-primary);
}
.brand-sub {
  font-size: 12px;
  color: var(--ws-text-light);
}
</style>
