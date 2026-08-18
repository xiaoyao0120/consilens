<script setup>
import { watch } from "vue";
import { useRoute } from "vue-router";
import { useAppStore } from "@/store";
import SideMenu from "./SideMenu.vue";

const route = useRoute();
const appStore = useAppStore();

// 主题类同步到 <html>：naive-ui 的抽屉/弹窗默认 teleport 到 body，
// 挂在根元素上才能让全局 CSS 变量（--card/--border/...）在弹层内继承。
watch(
  () => appStore.theme,
  (theme) => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    document.documentElement.classList.toggle("bright", theme !== "dark");
  },
  { immediate: true }
);
</script>

<template>
  <div class="app-layout" :class="[appStore.theme]">
    <n-layout has-sider position="absolute" style="top: 0; bottom: 0">
      <n-layout-sider
        bordered
        collapse-mode="width"
        :width="220"
        :collapsed-width="64"
        v-model:collapsed="appStore.collapsed"
        show-trigger="bar"
      >
        <div class="brand" :class="{ collapsed: appStore.collapsed }">
          <span class="brand-mark">C</span>
          <span v-show="!appStore.collapsed" class="brand-name">Consilens</span>
        </div>
        <SideMenu />
      </n-layout-sider>
      <n-layout>
        <n-layout-content class="layout-content">
          <router-view v-slot="{ Component }">
            <transition name="fade-slide" mode="out-in">
              <!-- key 用 path 而非 fullPath：query（如 ?tab=）变化不重建页面 -->
              <component :is="Component" :key="route.path" />
            </transition>
          </router-view>
        </n-layout-content>
      </n-layout>
    </n-layout>
  </div>
</template>

<style scoped lang="scss">
.app-layout {
  height: 100%;
  overflow: hidden;

  // naive n-layout 内部滚动容器默认 auto——禁用，避免页面切换瞬间出现/消失滚动条
  :deep(.n-layout-scroll-container) {
    overflow: hidden;
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 18px;
  border-bottom: 1px solid var(--border);

  &.collapsed {
    padding: 0;
    justify-content: center;
  }
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--primary);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  flex-shrink: 0;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
}

.layout-content {
  background: var(--bg);
  height: 100%;
  overflow: hidden;
}
</style>
