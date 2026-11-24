# 编译期依赖（不打进模块）

| 文件 | 用途 | 来源 | 许可 |
|---|---|---|---|
| `api-102.jar` | libxposed API 102 编译桩，运行期由 LSPosed 框架提供 | https://github.com/libxposed/api | Apache-2.0 |
| `annotation-androidx.jar` | 编译期注解 | AndroidX | Apache-2.0 |
| `annotation-libxposed.jar` | libxposed 注解 | libxposed | Apache-2.0 |

`api-102.jar` 只参与 javac，不进 d8；模块 dex 里不含框架类，由 LSPosed 在运行时提供。
