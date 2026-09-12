<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">
        <span class="brand-dots"><i class="d1" /><i class="d2" /><i class="d3" /></span>
        <span class="brand-name">问书</span>
        <span class="brand-sub">把备考资料丢进来 —— 问它 · 查它 · 让它考你</span>
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
  background: linear-gradient(120deg, #fdfcf9 60%, var(--ws-blue-soft));
  height: 56px;
}
.brand {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-dots {
  display: inline-flex;
  gap: 4px;
}
.brand-dots i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.brand-dots .d1 {
  background: var(--ws-blue);
}
.brand-dots .d2 {
  background: var(--ws-green);
}
.brand-dots .d3 {
  background: var(--ws-orange);
}
.brand-name {
  font-size: 21px;
  font-weight: 800;
  letter-spacing: 2px;
  color: var(--ws-ink);
}
.brand-sub {
  font-size: 12px;
  color: var(--ws-ink-light);
}
</style>
