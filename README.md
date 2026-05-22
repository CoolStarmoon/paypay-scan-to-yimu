# PayPay 自动记账

一个面向日本 PayPay 使用记录截图的 Android 小工具：批量导入 PayPay 交易记录截图，自动识别商户、时间和金额，并导出为一木记账可导入的 Excel 表格。

> 项目还在个人使用和逐步开源整理阶段，当前更偏“能解决实际问题的小工具”，不是完整商业 App。

## 这个项目解决什么问题

PayPay 的消费记录截图里有大量重复信息，手动录入到记账软件很麻烦。本项目希望把流程简化为：

```text
选择 PayPay 截图
 -> OCR 识别交易记录
 -> 自动去重
 -> 按用户分类映射自动分类
 -> 导出一木记账 xls
```

目前主要适配：

- PayPay 使用记录截图
- 日文商户名识别
- 一木记账导入表格格式
- Android 本地离线处理

## 功能特性

- 批量选择 PayPay 截图
- 使用 Google ML Kit Japanese Text Recognition 进行日文 OCR
- 从截图中提取商户、日期时间、金额
- 自动过滤重复账单
- 通过“商户合集 + 用户分类映射”进行自动分类
- 首次使用时配置每个商户合集对应的一木记账一级/二级分类
- 在识别结果中点击账单即可修正分类，并记住为该商户单独规则
- 支持导入/导出 `category_mapping.csv`、`merchant_override.csv`、`rule_groups.csv`
- 导出 `.xls` 表格到系统 Download 目录
- 适配 Android 6.0 及以上

## 当前状态

当前版本已经完成第一版分类解耦：

- 内置 8 个商户合集：便利店、超市、快餐、正餐、服装店、电车地铁、订阅服务、电影娱乐
- 用户自己的分类体系保存在本地配置中，不再硬编码到源码里
- 未匹配商户会进入“待分类”，不会阻塞导出
- release 仍使用 debug 签名配置，不适合作为正式商店发布包

详细需求见：

- [商户合集与用户分类映射需求文档](docs/category-mapping-requirements.md)

## 技术栈

- Android 原生开发
- Java
- Gradle
- Google ML Kit Text Recognition Japanese
- JExcelAPI

主要依赖：

```gradle
implementation "com.google.mlkit:text-recognition-japanese:16.0.1"
implementation "net.sourceforge.jexcelapi:jxl:2.6.12"
```

## 项目结构

```text
.
├── app/
│   └── src/main/
│       ├── java/com/hoshitsuki/paypayledger/
│       │   └── MainActivity.java
│       ├── res/
│       └── AndroidManifest.xml
├── docs/
│   └── category-mapping-requirements.md
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 本地构建

### 环境要求

- Android Studio
- JDK 8 或以上
- Android SDK 33
- Gradle 环境由 Android Studio 自动处理即可

### 构建步骤

1. 克隆仓库：

```bash
git clone https://github.com/CoolStarmoon/paypay-scan-to-yimu.git
cd paypay-scan-to-yimu
```

2. 使用 Android Studio 打开项目。

3. 等待 Gradle Sync 完成。

4. 连接 Android 设备或启动模拟器。

5. 运行 `app`。

也可以命令行构建：

```bash
./gradlew assembleDebug
```

Windows PowerShell 下：

```powershell
.\gradlew.bat assembleDebug
```

## 使用方式

1. 在 PayPay 中打开使用记录页面并截图。
2. 打开本 App。
3. 点击选择 PayPay 截图。
4. 可一次选择多张截图。
5. 等待 OCR 识别完成。
6. App 会自动导出一木记账可导入的 `.xls` 文件到 Download 目录。
7. 在一木记账中导入该文件。

## 分类规则说明

当前分类体系：

```text
商户名
 -> 匹配商户合集，例如便利店、服装店、订阅服务
 -> 用户为每个合集设置自己的一级/二级分类
 -> 导出到一木记账
```

举例：

```text
便利店 -> 餐饮 / 零食
服装店 -> 购物 / 空
订阅服务 -> 生活 / App会员
```

代码里维护的是通用商户合集，用户自己的账本分类通过首次设置、设置页或 CSV 导入维护。

## 注意事项

- OCR 识别准确率会受截图清晰度、字体大小、系统语言和 PayPay 页面布局影响。
- 当前主要面向日文 PayPay 记录，其他语言或地区格式未充分测试。
- 导出的表格格式以一木记账为目标，其他记账软件可能需要调整字段。
- Android 10 以下写入 Download 目录需要存储权限。
- 当前 release 使用 debug 签名配置，不适合作为正式发布包。

## 后续计划

- [x] 重构硬编码分类规则
- [x] 增加首次分类映射设置
- [x] 支持商户单独修正规则
- [x] 支持导入/导出分类配置
- [x] 整理 UI 文案和编码问题
- [ ] 增加示例截图和导出表格说明
- [ ] 提供正式 release 包

## 贡献

欢迎提交 issue 或 PR，尤其是：

- PayPay 不同页面样式的识别问题
- 常见日本商户关键词补充
- 一木记账导入字段适配
- 分类规则设计建议
- Android 兼容性问题

如果涉及真实账单截图，请注意打码金额、商户、时间等个人信息。

## License

暂未指定开源协议。正式公开前建议补充明确的 License。
