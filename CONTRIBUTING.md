# 参与开发

先阅读 [README](README.md)、[开发约定](docs/development.md)与 [AGENTS.md](AGENTS.md)。

1. 安装指定工具链，运行 `just setup`。
2. 从 main 创建功能分支；提交采用 `feat:`、`fix:`、`docs:`、`chore:` 等 Conventional Commits 前缀。
3. 修改后运行 `just check`；涉及打包运行 `just package`。
4. 协议变更必须维护共享样例、三语言测试与兼容性说明；变更记录写入根目录 changelog/<版本号>.md。升级统一版本使用 just set-version，不分散手动改版本。
5. PR 写明行为变化、验证证据、迁移和未完成范围；不将设计接口描述为可调用接口。

本项目采用 [Apache License 2.0](LICENSE)。提交用于合入项目的贡献按该许可证提供；保留适用的版权与 NOTICE 声明。源码公开与包注册表发布分别管理，包发布仍须完成文档中的配置。
