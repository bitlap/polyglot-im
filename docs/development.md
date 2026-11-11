# 开发与配置

## 工具链

使用 JDK 17+、sbt 1.10.7、Scala 3.6.3、Flutter 3.44.6 / Dart 3.12.2、Python 3.11+、uv 0.11.32、just 1.40.0。CI 固定版本；本机版本变化须通过检查后再更新基线。

```sh
just setup
just check
```

just 不安装全局工具链，不是跨语言依赖解析器。Python 使用 uv.lock，Dart/Flutter 各自提交 pubspec.lock，Scala 使用固定直接依赖版本与 sbt 版本。Scala 当前未提供完整传递依赖锁，不能声称全仓已达到密闭可复现构建。

## 目录与代码风格

- Scala：包名 `sf.im`，两空格缩进，Scala 3；Circe 处理 JSON，Scalafmt 120 列。组织标识暂用现有后端的 `cn.dreamylost`，Maven 发布前单独验证归属。
- Dart：纯协议放 sf_im_client；Flutter API 放 sf_im_flutter。使用 dart format、推荐 lints，不把 Widgets 或 dart:io 引入纯 Dart 协议层。
- Python：包名 sf_im，四空格缩进，类型注解，Ruff 格式及检查；当前模型无运行时第三方依赖。
- 协议：snake_case 字段；字符串 ID 和序号；payload 支持未知类型回退。
- 文档：中文、相对链接、明确已实现与规划；复杂流程使用 Mermaid。

当前样例和配置只使用虚构数据。不得复制现有项目 .env、生产数据库连接、Apple 私钥或推送令牌。

## 当前服务配置

| 名称 | 默认 | 当前是否生效 |
| --- | --- | --- |
| SF_IM_HOST | 127.0.0.1 | 是，仅监听地址 |
| SF_IM_PORT | 8090 | 是，TCP 端口 |
| SF_IM_DATABASE_URL | 无 | 否，规划项 |
| SF_IM_REDIS_URL | 无 | 否，规划项 |
| SF_IM_LOG_LEVEL | 无 | 否，规划项 |

`.env.example` 与 [server.example.yaml](../config/server.example.yaml) 是无凭证参考。当前服务只读取上述两个环境变量；不会自动加载 .env 或 YAML，避免误以为已经连接数据库。

```sh
SF_IM_PORT=8091 just run-server
```

未来配置加载统一使用“默认值 → 配置文件 → 环境变量”的覆盖顺序，并对必需配置启动时校验。生产密钥只允许从受控机密服务注入；不写进仓库或客户端包。

## 客户端身份与存储约定（规划）

缓存命名空间使用 environment + app_id + user_id。device_id 是应用安装身份，不采用不可重置的硬件标识。刷新 token 通过 tokenProvider 注入并合并并发刷新，不在 SDK 日志输出。

账号/服务环境切换时先撤销当前任务代次、断开连接、取消发送与同步、释放数据库，再打开新命名空间。每个异步回调验证所属代次；旧账号事件不得影响新账号列表、红点或导航。

## 协议变更流程

1. 在设计和功能矩阵中明确功能、权限、失败与多端语义。
2. 更新 Schema / OpenAPI 与共享样例；增加非法输入和兼容性用例。
3. 修改 Scala、Dart、Python 编解码及契约测试。
4. 运行 just check；协议增量必须考虑旧 SDK 行为。
5. 更新集成说明、CHANGELOG、迁移说明与实际验证记录。

## 参考项目

只参考目录、格式和工具选择：`sf-app` 的中文文档与 Flutter CI；`snapflow` 的 Scala/Circe/Scalafmt；`sf-admin` 的 EditorConfig。旧项目中的部署方式、鉴权和生产地址不自动成为 sf-im 的授权或配置。
