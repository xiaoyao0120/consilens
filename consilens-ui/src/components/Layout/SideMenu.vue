<script setup>
import { h, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { NIcon } from "naive-ui";
import { useAppStore } from "@/store";
import {
  GridOutline,
  PlayCircleOutline,
  PulseOutline,
  ServerOutline,
  LinkOutline,
  SunnyOutline,
  MoonOutline,
} from "@vicons/ionicons5";

function renderIcon(icon) {
  return () => h(NIcon, null, { default: () => h(icon) });
}

const menuOptions = [
  { label: "工作台", key: "/dashboard", icon: renderIcon(GridOutline) },
  { label: "任务定义", key: "/definitions", icon: renderIcon(PlayCircleOutline) },
  { label: "运行实例", key: "/instances", icon: renderIcon(PulseOutline) },
  { label: "数据源", key: "/datasources", icon: renderIcon(LinkOutline) },
  { label: "节点与集群", key: "/nodes", icon: renderIcon(ServerOutline) },
];

const route = useRoute();
const router = useRouter();
const appStore = useAppStore();

// 详情页（/definitions/:id、/instances/:id）时保持父菜单高亮
const activeKey = computed(() => {
  if (route.path.startsWith("/definitions/") && route.path !== "/definitions/new") return "/definitions";
  if (route.path.startsWith("/instances/")) return "/instances";
  return route.path;
});

function handleSelect(key) {
  router.push(key);
}
</script>

<template>
  <div class="side-menu-wrap">
    <n-menu
      class="side-menu"
      :options="menuOptions"
      :value="activeKey"
      :collapsed="appStore.collapsed"
      :collapsed-width="64"
      :collapsed-icon-size="22"
      :root-indent="20"
      :indent="20"
      @update:value="handleSelect"
    />
    <div class="side-footer" :class="{ collapsed: appStore.collapsed }">
      <n-tooltip placement="right">
        <template #trigger>
          <n-button quaternary circle size="small" @click="appStore.toggleTheme()">
            <template #icon>
              <n-icon :size="18">
                <component :is="appStore.theme === 'dark' ? SunnyOutline : MoonOutline" />
              </n-icon>
            </template>
          </n-button>
        </template>
        {{ appStore.theme === "dark" ? "切换到亮色" : "切换到暗色" }}
      </n-tooltip>
    </div>
  </div>
</template>

<style scoped lang="scss">
.side-menu-wrap {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 57px);
}

.side-menu {
  flex: 1;
  overflow-y: auto;
}

.side-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 0;
  border-top: 1px solid var(--border);

  &.collapsed {
    padding: 10px 0;
  }
}
</style>
