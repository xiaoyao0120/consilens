<script setup>
import { useRouter } from "vue-router";
import { useLoadingBar } from "naive-ui";

// 路由切换顶栏进度条：必须在 n-loading-bar-provider 内使用
const router = useRouter();
const bar = useLoadingBar();

router.beforeEach(() => {
  bar.start();
});
router.afterEach(() => {
  bar.finish();
});
router.onError(() => {
  bar.error();
});
</script>

<template>
  <span style="display: none" />
</template>
