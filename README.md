# 烨昕的课表 · iOS 原生课程表

SwiftUI 原生应用，界面使用液态玻璃风格。iOS 26 使用系统 Liquid Glass，iOS 17–18 使用磨砂材料回退。兼容 iPhone 和 iPad，完全离线，不需要学校账号、服务器或 API Key。

已实现周课表、今天视图、学期切换与编辑、第1周起始日期、每节课开始/结束时间、手动添加/编辑/删除、截图导入、表格导入、备份恢复，以及桌面和锁屏 WidgetKit 小组件。只提供这三个主要入口，不加入聊天和社交功能。

## 打开工程

1. 在安装了 **Xcode 26 或更高版本**的 Mac 上解压。
2. 打开 `ClearClass.xcodeproj`，选择 `ClearClass` Scheme。
3. 先选择 iOS 模拟器运行，无需修改学校信息。
4. 真机运行时，在 `Configuration/Settings.xcconfig` 中设置你自己的 `APP_BUNDLE_ID` 和 `DEVELOPMENT_TEAM`。`APP_GROUP_ID` 会跟随应用标识变化。
5. 为 **ClearClass 与 ClearClassWidgets 两个 Target**配置同一开发团队，并在 Signing & Capabilities 中启用同一个 App Group。真实设备上的共享权限需要相应的签名配置和描述文件支持。
6. 选择 iPhone 后运行。第一次启动是空课表，可导入自己的截图或表格，也可以使用明确标注的示例。

本次交付环境是 Linux，没有 Xcode/iOS SDK，**未编译或签名 IPA，未在 iPhone 上运行，未实际执行 Vision OCR 或 XCTest**。工程文件、Swift 语法树、表格实际读取和预览可以在当前环境检查；这不代表 iOS 编译已通过。

## 导入截图

在“课表”右上角或“设置”中点“导入课表”→“课表截图”，选择教务处的**学期理论课表**截图，保留星期一到星期日表头，点“识别截图”。通过本机 Apple Vision 做简体中文与英文识别，然后按星期列解析课程、教师、周次、节次和教室。

支持同格多门课程、跨行文字、`1-4,6-8`、`3,7,9,11,14,16`、单双周，以及 `[01-02-03-04节]` 的跨节课。相同课程的跨行重复会去重。识别结果必须经过确认页；可以取消某门课、逐门修改，选择学期，然后追加或替换。

若图片没有星期表头，先框选**周一至周日的七个等宽课程列**，去掉左侧节次列，开启“没有星期表头”。程序不会在不确定星期时自动猜测导入。拍照透视变形、裁掉部分星期、非七列布局和其他教务格式不保证自动识别。

识别准确率仍需要真实设备验证。截图识别结果中的教师/教室可能为空，低置信度结果会提示核对。截图中的 O/P 调课标记不会被用于自动取消其他课程；标记与重叠会提示人工核对。

## 导入表格

在“导入课表”→“课表表格”中选择 `.xls`、`.xlsx`、`.csv` 或 `.tsv`。

- 支持安徽工业大学教务导出的星期矩阵，每格可包含多门课程，包括 `1-13[周]`、`[01-02]节` 和 `[09-10-11-12]节`。
- 支持按行排列的表格，表头使用“课程名称、星期、节次、周次、教师、教室”；也支持“开始节次、结束节次”。
- 支持多个工作表，完整个人课表上限10个工作表，每表500行、200列，文件不超过10 MB。加密文件不支持。
- 表格标注的学期会带入确认页，与当前学期不同时默认新建；**开学日期需要你按校历确认**。不会根据文件打印日期推断开学日期。
- 备注中没有固定星期、节次的课程会显示说明，不会编造上课安排。
- 追加时按课程名、教师、教室、星期、节次和周次去重；替换时只替换你选择的那个学期。

表格读取使用随包附带的 SheetJS CE 0.20.3，经 JavaScriptCore 执行。代码不下载脚本，不执行表格里的宏，也不发送文件到服务器。第三方许可证见 `Resources/SHEETJS-LICENSE.txt`。

## 小组件

运行应用并保存课表后，长按桌面→编辑→添加小组件→“烨昕的课表”。

- 小尺寸：下一门课/正在上课，时间与教室。
- 中尺寸：今天尚未结束的课程。
- 大尺寸：今日完整课程列表，最多展示7门，并提示其余课程数。
- 锁屏：行内、矩形和圆形，显示下一门课或今日剩余课程数。

小组件与 App 使用同一个 App Group 文件，读取同一个选中学期。保存课程、作息或学期后请求刷新，并提前安排上课、下课和跨日的时间线。**系统决定实际刷新时机，并非秒级实时刷新。**点击可打开应用或课程详情。

只有普通 App 的签名或删掉扩展的安装方式，不能提供完整小组件。App 能在缺少共享权限时保存本地课表，但会在设置页提示小组件不可用。将来更换包标识或 App Group 前请先导出备份。

## 生成安装包

Mac 终端执行：

```bash
bash Scripts/build-unsigned.sh
```

生成 `build/ClearClass-unsigned.ipa`，包含 Widget 扩展。这是**待签名包**，无法直接点开安装。要真正安装并使用小组件，需要给 App 和 Widget 扩展完整签名并保留同一 App Group 权限。使用 Xcode 的 Product→Archive→Distribute App，可按你的开发账号导出已签名版本。

工程含 `.github/workflows/ios.yml`：放入自己的 GitHub 仓库后可以手动运行，使用 Xcode 26 的 macOS Runner，执行原生测试并输出未签名 IPA。本次没有创建仓库或启动云端构建。

## 验证与文件

`VALIDATION.md` 列出本次实际执行的检查和未执行项目。`Tests/ScheduleTests.swift` 覆盖日期边界、单双周、跨节课、重叠、截图结构解析与备份验证；需在 Xcode 中按 Cmd+U 执行。

本地表格回归检查：

```bash
node Scripts/test-spreadsheet.cjs
```

`Preview/preview.html` 是与原生布局对应的**交互式设计预览**，方便检查浅色/深色、周次、课程详情和表格导入。它不是 iOS 安装包，不提供原生 Vision 或系统小组件。预览中的表格导入也使用工程里同一份实际读取代码。

如修改文件列表，可执行 `python3 Scripts/generate_project.py` 重建工程。默认时间表中晚上第11、12节为可编辑示例，以学校最新作息为准。示例学期第1周的周一 `2026-08-31` 由截图中第6周为10月5日反推，使用前请按校历核对。

官方实现参考：

- https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views
- https://developer.apple.com/documentation/vision/recognizing-text-in-images
- https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date
- https://docs.sheetjs.com/docs/demos/engines/jsc/
