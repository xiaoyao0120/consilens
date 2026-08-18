const Layout = () => import("@/components/Layout/index.vue");

export const menuRoutes = [
  {
    path: "/dashboard",
    name: "dashboard",
    component: () => import("@/views/dashboard/index.vue"),
    meta: { title: "工作台", icon: "grid-outline" },
  },
  {
    path: "/ai",
    name: "ai-assistant",
    component: () => import("@/views/aiAssistant/index.vue"),
    meta: { title: "AI 助手", icon: "sparkles-outline" },
  },
  {
    path: "/definitions",
    name: "definitions",
    component: () => import("@/views/definitions/index.vue"),
    meta: { title: "任务定义", icon: "play-circle-outline" },
  },
  {
    path: "/definitions/new",
    name: "definition-new",
    component: () => import("@/views/definitions/new.vue"),
    meta: { title: "新建定义", hidden: true },
  },
  {
    path: "/definitions/:definitionId",
    name: "definition-detail",
    component: () => import("@/views/definitions/detail.vue"),
    meta: { title: "定义详情", hidden: true },
  },
  {
    path: "/instances",
    name: "instances",
    component: () => import("@/views/instances/index.vue"),
    meta: { title: "运行实例", icon: "pulse-outline" },
  },
  {
    path: "/instances/:taskId",
    name: "instance-detail",
    component: () => import("@/views/instances/detail.vue"),
    meta: { title: "实例详情", hidden: true },
  },
  {
    path: "/artifacts",
    name: "artifacts",
    component: () => import("@/views/artifacts/index.vue"),
    meta: { title: "Artifact 中心", icon: "albums-outline" },
  },
  {
    path: "/artifacts/:artifactId",
    name: "artifact-detail",
    component: () => import("@/views/artifacts/detail.vue"),
    meta: { title: "Artifact 详情", hidden: true },
  },
  {
    path: "/datasources",
    name: "datasources",
    component: () => import("@/views/datasources/index.vue"),
    meta: { title: "数据源", icon: "link-outline" },
  },
  {
    path: "/nodes",
    name: "nodes",
    component: () => import("@/views/nodes/index.vue"),
    meta: { title: "节点与集群", icon: "server-outline" },
  },
];

const routes = [
  {
    path: "/",
    component: Layout,
    redirect: "/dashboard",
    children: menuRoutes,
  },
];

export default routes;
